#include "core/AudioRecorder.h"
#include "core/miniaudio.h"
#include "core/Logger.h"
#include <vector>
#include <mutex>

struct AudioRecorder::Impl {
    Config config;
    ma_device device;
    ma_encoder encoder;
    SilenceDetector* detector = nullptr;
    std::atomic<bool> isRecording{false};
    std::vector<int16_t> processingBuffer;
    std::mutex statsMutex;

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
};

void AudioRecorder::data_callback(void* pDevicePtr, void* pOutput, const void* pInput, uint32_t frameCount) {
    ma_device* pDevice = (ma_device*)pDevicePtr;
    AudioRecorder::Impl* pImpl = (AudioRecorder::Impl*)pDevice->pUserData;
    if (pImpl == nullptr || pInput == nullptr) return;

    const int16_t* pInputS16 = (const int16_t*)pInput;

    if (pImpl->detector) {
        if (pImpl->processingBuffer.size() < frameCount + 4096) {
            pImpl->processingBuffer.resize(frameCount + 4096);
        }

        int numOutSamples = (int)pImpl->processingBuffer.size();
        pImpl->detector->process(pInputS16, (int)frameCount, pImpl->processingBuffer.data(), numOutSamples, (int)pImpl->processingBuffer.size());

        if (numOutSamples > 0) {
            if (!pImpl->config.output_file.empty()) {
                ma_encoder_write_pcm_frames(&pImpl->encoder, pImpl->processingBuffer.data(), (ma_uint64)numOutSamples, NULL);
            }
            if (pImpl->config.on_data) {
                pImpl->config.on_data(pImpl->processingBuffer.data(), (size_t)numOutSamples);
            }
        }
    } else {
        if (!pImpl->config.output_file.empty()) {
            ma_encoder_write_pcm_frames(&pImpl->encoder, pInputS16, (ma_uint64)frameCount, NULL);
        }
        if (pImpl->config.on_data) {
            pImpl->config.on_data(pInputS16, (size_t)frameCount);
        }
    }

    (void)pOutput;
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

    if (ma_device_start(&pImpl->device) != MA_SUCCESS) {
        Logger::error("Failed to start capture device.");
        ma_device_uninit(&pImpl->device);
        if (!pImpl->config.output_file.empty()) {
            ma_encoder_uninit(&pImpl->encoder);
        }
        return false;
    }

    pImpl->isRecording = true;
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
    if (pImpl->detector) {
        return pImpl->detector->getStats();
    }
    HushStats stats = {0, 0, 0, 0, 0};
    return stats;
}

double AudioRecorder::getCurrentDb() const {
    if (pImpl->detector) {
        return pImpl->detector->getCurrentDb();
    }
    return -100.0;
}
