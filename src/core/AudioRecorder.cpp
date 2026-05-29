#include "core/AudioRecorder.h"
#include "core/miniaudio.h"
#include "core/Logger.h"
#include "core/RingBuffer.h"
#include "core/FlowController.h"
#include <vector>
#include <mutex>
#include <thread>
#include <chrono>
#include <memory>
#include <algorithm>
#include <iostream>


struct AudioRecorder::Impl {
    Config config;
    ma_device device;
    ma_encoder encoder;
    SilenceDetector* detector = nullptr;
    std::atomic<bool> isRecording{false};
    std::atomic<bool> bufferOverflow{false};
    std::mutex statsMutex;

    std::unique_ptr<RingBuffer<int16_t>> ringBuffer;
    std::unique_ptr<FlowController> flowController;
    std::thread workerThread;

    Impl(const Config& cfg) : config(cfg) {
        if (config.use_silence_removal) {
            HushConfig hcfg;
            hcfg.thresholdDb = config.threshold_db;
            hcfg.aggressionLevel = config.aggression_level;
            hcfg.sampleRate = config.sample_rate;
            detector = new SilenceDetector(hcfg);
        }
    }

    ~Impl() {
        if (isRecording) {
            stop();
        }
        delete detector;
    }

    void stop() {
        if (!isRecording) return;
        isRecording = false;

        ma_device_stop(&device);

        if (workerThread.joinable()) {
            workerThread.join();
        }

        if (detector) {
            int numOutSamples = 0;
            std::vector<int16_t> flushBuffer(config.sample_rate);
            detector->flush(flushBuffer.data(), numOutSamples, (int)flushBuffer.size());
            if (numOutSamples > 0 && !config.output_file.empty()) {
                ma_encoder_write_pcm_frames(&encoder, flushBuffer.data(), (ma_uint64)numOutSamples, NULL);
            }
        }

        ma_device_uninit(&device);
        if (!config.output_file.empty()) {
            ma_encoder_uninit(&encoder);
        }
    }

    void workerLoop();
};

void AudioRecorder::data_callback(void* pDevicePtr, void* pOutput, const void* pInput, uint32_t frameCount) {
    ma_device* pDevice = (ma_device*)pDevicePtr;
    AudioRecorder::Impl* pImpl = (AudioRecorder::Impl*)pDevice->pUserData;
    if (pImpl == nullptr || pInput == nullptr) return;

    const int16_t* pInputS16 = (const int16_t*)pInput;
    if (pImpl->ringBuffer) {
        size_t pushed = pImpl->ringBuffer->push(pInputS16, frameCount);
        if (pushed == 0 && frameCount > 0) {
            pImpl->bufferOverflow.store(true, std::memory_order_relaxed);
        }
        if (pImpl->flowController) {
            pImpl->flowController->update(pImpl->ringBuffer->size(), pImpl->ringBuffer->capacity());
        }
    }

    (void)pOutput;
}

void AudioRecorder::Impl::workerLoop() {
    // Preallocate local buffers for the worker thread
    std::vector<int16_t> popBuffer(2048);
    std::vector<int16_t> workerProcessingBuffer(2048);

    while (isRecording.load(std::memory_order_relaxed) || (ringBuffer && ringBuffer->size() > 0)) {
        if (!ringBuffer) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }
        size_t available = ringBuffer->size();
        size_t capacity = ringBuffer->capacity();
        if (flowController) {
            flowController->update(available, capacity);
        }

        if (available == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        size_t toPop = std::min(available, popBuffer.size());
        size_t popped = ringBuffer->pop(popBuffer.data(), toPop);
        if (popped == 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
            continue;
        }

        if (detector) {
            int numOutSamples = static_cast<int>(workerProcessingBuffer.size());
            FlowState currentState = FlowState::NORMAL;
            if (flowController) {
                currentState = flowController->getCurrentState();
            }
            {
                std::lock_guard<std::mutex> lock(statsMutex);
                detector->process(popBuffer.data(), static_cast<int>(popped), workerProcessingBuffer.data(), numOutSamples, numOutSamples, currentState);
            }

            if (numOutSamples > 0) {
                if (!config.output_file.empty()) {
                    ma_encoder_write_pcm_frames(&encoder, workerProcessingBuffer.data(), (ma_uint64)numOutSamples, NULL);
                }
                if (config.on_data) {
                    config.on_data(workerProcessingBuffer.data(), (size_t)numOutSamples);
                }
            }
        } else {
            if (!config.output_file.empty()) {
                ma_encoder_write_pcm_frames(&encoder, popBuffer.data(), (ma_uint64)popped, NULL);
            }
            if (config.on_data) {
                config.on_data(popBuffer.data(), popped);
            }
        }
    }
}

AudioRecorder::AudioRecorder(const Config& config) : pImpl(new Impl(config)) {}

AudioRecorder::~AudioRecorder() {
    delete pImpl;
}

bool AudioRecorder::start() {
    if (pImpl->isRecording) return false;

    if (!pImpl->config.output_file.empty()) {
        ma_encoder_config encoderConfig = ma_encoder_config_init(ma_encoding_format_wav, ma_format_s16, 1, pImpl->config.sample_rate);
        if (ma_encoder_init_file(pImpl->config.output_file.c_str(), &encoderConfig, &pImpl->encoder) != MA_SUCCESS) {
            Logger::error("Failed to initialize encoder for %s", pImpl->config.output_file.c_str());
            return false;
        }
    }

    ma_device_config deviceConfig = ma_device_config_init(ma_device_type_capture);
    deviceConfig.capture.format   = ma_format_s16;
    deviceConfig.capture.channels = 1;
    deviceConfig.sampleRate        = pImpl->config.sample_rate;
    deviceConfig.dataCallback      = (ma_device_data_proc)AudioRecorder::data_callback;
    deviceConfig.pUserData         = pImpl;

    if (ma_device_init(NULL, &deviceConfig, &pImpl->device) != MA_SUCCESS) {
        Logger::error("Failed to initialize capture device.");
        if (!pImpl->config.output_file.empty()) {
            ma_encoder_uninit(&pImpl->encoder);
        }
        return false;
    }

    // Preallocate RingBuffer capacity based on sample rate and period size
    uint32_t periodSize = pImpl->device.capture.internalPeriodSizeInFrames;
    if (periodSize == 0) periodSize = 1024;
    uint32_t bufferCapacity = std::max(periodSize * 4, static_cast<uint32_t>(pImpl->config.sample_rate));

    pImpl->ringBuffer = std::make_unique<RingBuffer<int16_t>>(bufferCapacity);
    
    FlowController::Config flowCfg;
    flowCfg.sampleRate = pImpl->config.sample_rate;
    pImpl->flowController = std::make_unique<FlowController>(flowCfg);

    pImpl->bufferOverflow.store(false, std::memory_order_relaxed);

    pImpl->isRecording = true;
    pImpl->workerThread = std::thread(&AudioRecorder::Impl::workerLoop, pImpl);

    if (ma_device_start(&pImpl->device) != MA_SUCCESS) {
        Logger::error("Failed to start capture device.");
        pImpl->isRecording = false;
        if (pImpl->workerThread.joinable()) {
            pImpl->workerThread.join();
        }
        ma_device_uninit(&pImpl->device);
        if (!pImpl->config.output_file.empty()) {
            ma_encoder_uninit(&pImpl->encoder);
        }
        return false;
    }

    return true;
}

void AudioRecorder::stop() {
    pImpl->stop();
}

void AudioRecorder::setDataCallback(DataCallback callback) {
    pImpl->config.on_data = callback;
}

bool AudioRecorder::isRecording() const {
    return pImpl->isRecording;
}

HushStats AudioRecorder::getStats() const {
    std::lock_guard<std::mutex> lock(pImpl->statsMutex);
    if (pImpl->detector) {
        return pImpl->detector->getStats();
    }
    HushStats stats = {0, 0, 0, 0, 0};
    return stats;
}

double AudioRecorder::getCurrentDb() const {
    std::lock_guard<std::mutex> lock(pImpl->statsMutex);
    if (pImpl->detector) {
        return pImpl->detector->getCurrentDb();
    }
    return -100.0;
}

int AudioRecorder::getPressureLevel() const {
    if (pImpl->flowController) {
        return pImpl->flowController->getPressureLevel();
    }
    return 0;
}

int AudioRecorder::getLatencyEstimateMs() const {
    if (pImpl->flowController) {
        return pImpl->flowController->getLatencyEstimateMs();
    }
    return 0;
}

uint64_t AudioRecorder::getDropCount() const {
    if (pImpl->ringBuffer) {
        return pImpl->ringBuffer->getDropCount();
    }
    return 0;
}

int AudioRecorder::getDegradationState() const {
    if (pImpl->flowController) {
        return static_cast<int>(pImpl->flowController->getCurrentState());
    }
    return 0;
}


size_t AudioRecorder::injectAudioSamples(const int16_t* samples, size_t count) {
    if (pImpl && pImpl->ringBuffer) {
        size_t pushed = pImpl->ringBuffer->push(samples, count);
        if (pushed == 0 && count > 0) {
            pImpl->bufferOverflow.store(true, std::memory_order_relaxed);
        }
        if (pImpl->flowController) {
            pImpl->flowController->update(pImpl->ringBuffer->size(), pImpl->ringBuffer->capacity());
        }
        return pushed;
    }
    return 0;
}



