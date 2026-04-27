#include "codecs/AudioProcessor.h"
#include "core/Logger.h"
#include <cmath>
#include <stdexcept>
#include <iostream>
#include <vector>
#include <cstring>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/error.h>
}

static std::string ff_err2str(int errnum) {
    char errbuf[AV_ERROR_MAX_STRING_SIZE];
    av_make_error_string(errbuf, AV_ERROR_MAX_STRING_SIZE, errnum);
    return std::string(errbuf);
}

AudioProcessor::AudioProcessor() {}

AudioProcessor::~AudioProcessor() {
    closeResources();
}

void AudioProcessor::closeResources() {
    closeEncoder();
    if (inCodecCtx) avcodec_free_context(&inCodecCtx);
    if (inFormatCtx) avformat_close_input(&inFormatCtx);
    if (swrCtx) swr_free(&swrCtx);
    if (encoderSwrCtx) swr_free(&encoderSwrCtx);
    inCodecCtx = nullptr;
    inFormatCtx = nullptr;
    swrCtx = nullptr;
    encoderSwrCtx = nullptr;
}

bool AudioProcessor::openInputFile(const std::string& inputFile) {
    if (avformat_open_input(&inFormatCtx, inputFile.c_str(), nullptr, nullptr) != 0) {
        Logger::error("Could not open input file: %s", inputFile.c_str());
        return false;
    }
    if (avformat_find_stream_info(inFormatCtx, nullptr) < 0) {
        Logger::error("Could not find stream information.");
        return false;
    }
    audioStreamIndex = av_find_best_stream(inFormatCtx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (audioStreamIndex < 0) {
        Logger::error("Could not find audio stream.");
        return false;
    }
    return true;
}

bool AudioProcessor::initializeDecoder() {
    const AVCodec* decoder = avcodec_find_decoder(inFormatCtx->streams[audioStreamIndex]->codecpar->codec_id);
    if (!decoder) return false;
    inCodecCtx = avcodec_alloc_context3(decoder);
    if (!inCodecCtx || avcodec_parameters_to_context(inCodecCtx, inFormatCtx->streams[audioStreamIndex]->codecpar) < 0) return false;
    if (avcodec_open2(inCodecCtx, decoder, nullptr) < 0) return false;
    return true;
}

bool AudioProcessor::initializeResampler() {
    AVChannelLayout out_ch_layout = AV_CHANNEL_LAYOUT_MONO;
    AVSampleFormat out_sample_fmt = AV_SAMPLE_FMT_S16;
    int out_sample_rate = 16000;

    const AVChannelLayout* in_ch_layout_ptr = &inCodecCtx->ch_layout;
    AVChannelLayout default_ch_layout; 

    if (inCodecCtx->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC && inCodecCtx->ch_layout.nb_channels > 0) {
        av_channel_layout_default(&default_ch_layout, inCodecCtx->ch_layout.nb_channels);
        in_ch_layout_ptr = &default_ch_layout;
    }

    swr_alloc_set_opts2(&swrCtx, &out_ch_layout, out_sample_fmt, out_sample_rate,
                        in_ch_layout_ptr, inCodecCtx->sample_fmt, inCodecCtx->sample_rate, 0, nullptr);
    if (!swrCtx || swr_init(swrCtx) < 0) return false;
    return true;
}

ProcessingSummary AudioProcessor::process(const std::string& inputFile, const std::string& outputFile, double silenceThresholdDb, double aggressionLevel, bool dryRun) {
    closeResources();
    this->dryRun = dryRun;
    this->totalEncodedSamples = 0;

    if (!openInputFile(inputFile) || !initializeDecoder() || !initializeResampler()) {
        closeResources();
        return {0, 0, 0, 0, 0};
    }

    HushConfig config;
    config.thresholdDb = silenceThresholdDb;
    config.aggressionLevel = aggressionLevel;
    config.sampleRate = 16000;
    detector = std::make_unique<SilenceDetector>(config);

    if (!dryRun) {
        if (!setupEncoder(outputFile)) {
            closeResources();
            return {0, 0, 0, 0, 0};
        }
        if (avformat_write_header(outputFormatContext, nullptr) < 0) {
            closeResources();
            return {0, 0, 0, 0, 0};
        }
    }
    
    AVPacket* pkt = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    int ret;

    while (av_read_frame(inFormatCtx, pkt) >= 0) {
        if (pkt->stream_index == audioStreamIndex) {
            if ((ret = avcodec_send_packet(inCodecCtx, pkt)) < 0) break;
            while (ret >= 0) {
                ret = avcodec_receive_frame(inCodecCtx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) break;
                detectAndRemoveSilence(frame);
                av_frame_unref(frame);
            }
        }
        av_packet_unref(pkt);
    }
    
    detectAndRemoveSilence(nullptr); // Flush

    if (!dryRun && outputFormatContext) av_write_trailer(outputFormatContext);

    av_packet_free(&pkt);
    av_frame_free(&frame);

    HushStats stats = detector->getStats();
    ProcessingSummary summary;
    summary.inputDuration = (double)stats.totalInputSamples / 16000.0;
    summary.outputDuration = (double)stats.totalOutputSamples / 16000.0;
    summary.reductionPercentage = stats.reductionPercentage;
    summary.total_input_frames = stats.totalInputSamples;
    summary.total_output_frames = stats.totalOutputSamples;
    
    closeResources();
    return summary;
}

void AudioProcessor::detectAndRemoveSilence(AVFrame* decodedFrame) {
    auto send_samples_to_encoder = [&](const int16_t* samples, int count) {
        if (!encoderSetupDone || count <= 0) return;

        AVFrame* encFrame = av_frame_alloc();
        encFrame->nb_samples = count;
        encFrame->ch_layout = outputCodecContext->ch_layout;
        encFrame->format = outputCodecContext->sample_fmt;
        encFrame->sample_rate = outputCodecContext->sample_rate;
        if (av_frame_get_buffer(encFrame, 0) < 0) {
            av_frame_free(&encFrame);
            return;
        }

        if (encoderSwrCtx) {
            const uint8_t* in_ptr = (const uint8_t*)samples;
            swr_convert(encoderSwrCtx, encFrame->data, encFrame->nb_samples, &in_ptr, count);
        } else {
            memcpy(encFrame->data[0], samples, count * sizeof(int16_t));
        }

        encFrame->pts = totalEncodedSamples;
        totalEncodedSamples += count;

        int ret = avcodec_send_frame(outputCodecContext, encFrame);
        av_frame_free(&encFrame);

        if (ret < 0) {
            Logger::error("Encoder send frame error: %s", ff_err2str(ret).c_str());
            return;
        }

        AVPacket* pkt = av_packet_alloc();
        while (avcodec_receive_packet(outputCodecContext, pkt) >= 0) {
            av_packet_rescale_ts(pkt, outputCodecContext->time_base, outAudioStream->time_base);
            pkt->stream_index = outAudioStream->index;
            av_interleaved_write_frame(outputFormatContext, pkt);
            av_packet_unref(pkt);
        }
        av_packet_free(&pkt);
    };

    if (decodedFrame) {
        int max_out_samples = av_rescale_rnd(swr_get_delay(swrCtx, decodedFrame->sample_rate) + decodedFrame->nb_samples, 16000, decodedFrame->sample_rate, AV_ROUND_UP);
        std::vector<int16_t> resampled(max_out_samples);
        uint8_t* out_ptr = (uint8_t*)resampled.data();
        int out_samples = swr_convert(swrCtx, &out_ptr, max_out_samples, (const uint8_t**)decodedFrame->data, decodedFrame->nb_samples);

        if (out_samples > 0) {
            // Buffer must account for internal buffering (up to ANALYSIS_BLOCK_SIZE - 1)
            std::vector<int16_t> output(out_samples + 1152); 
            int produced = 0;
            detector->process(resampled.data(), out_samples, output.data(), produced);
            if (produced > 0) {
                send_samples_to_encoder(output.data(), produced);
            }
        }
    } else {
        std::vector<int16_t> output(1152);
        int produced = 0;
        detector->flush(output.data(), produced);
        if (produced > 0) {
            send_samples_to_encoder(output.data(), produced);
        }
        
        if (encoderSetupDone) {
            avcodec_send_frame(outputCodecContext, nullptr);
            AVPacket* pkt = av_packet_alloc();
            while (avcodec_receive_packet(outputCodecContext, pkt) >= 0) {
                av_packet_rescale_ts(pkt, outputCodecContext->time_base, outAudioStream->time_base);
                pkt->stream_index = outAudioStream->index;
                av_interleaved_write_frame(outputFormatContext, pkt);
                av_packet_unref(pkt);
            }
            av_packet_free(&pkt);
        }
    }
}

bool AudioProcessor::setupEncoder(const std::string& outputFilePath) {
    if (encoderSetupDone) return true;

    const AVOutputFormat* fmt = av_guess_format(nullptr, outputFilePath.c_str(), nullptr);
    if (!fmt) fmt = av_guess_format("mp3", nullptr, nullptr);
    if (avformat_alloc_output_context2(&outputFormatContext, fmt, nullptr, outputFilePath.c_str()) < 0) return false;

    const AVCodec* encoder = avcodec_find_encoder(fmt->audio_codec);
    if (!encoder) return false;
    
    outputCodecContext = avcodec_alloc_context3(encoder);
    if (!outputCodecContext) return false;
    
    outputCodecContext->sample_rate = 16000;
    outputCodecContext->ch_layout = AV_CHANNEL_LAYOUT_MONO;
    outputCodecContext->sample_fmt = encoder->sample_fmts ? encoder->sample_fmts[0] : AV_SAMPLE_FMT_S16;
    outputCodecContext->bit_rate = 128000;
    outputCodecContext->time_base = {1, 16000};

    if (outputCodecContext->sample_fmt != AV_SAMPLE_FMT_S16) {
        encoderSwrCtx = swr_alloc();
        AVChannelLayout mono = AV_CHANNEL_LAYOUT_MONO;
        swr_alloc_set_opts2(&encoderSwrCtx, &outputCodecContext->ch_layout, outputCodecContext->sample_fmt, 16000,
                            &mono, AV_SAMPLE_FMT_S16, 16000, 0, nullptr);
        swr_init(encoderSwrCtx);
    }
    
    if (avcodec_open2(outputCodecContext, encoder, nullptr) < 0) return false;

    outAudioStream = avformat_new_stream(outputFormatContext, nullptr);
    if (!outAudioStream) return false;
    avcodec_parameters_from_context(outAudioStream->codecpar, outputCodecContext);
    outAudioStream->time_base = outputCodecContext->time_base;

    if (!(fmt->flags & AVFMT_NOFILE)) {
        if (avio_open(&outputFormatContext->pb, outputFilePath.c_str(), AVIO_FLAG_WRITE) < 0) return false;
    }
    encoderSetupDone = true;
    return true;
}

void AudioProcessor::closeEncoder() {
    if (outputFormatContext) {
        if (outputFormatContext->pb) avio_closep(&outputFormatContext->pb);
        avformat_free_context(outputFormatContext);
        outputFormatContext = nullptr;
    }
    if (outputCodecContext) {
        avcodec_free_context(&outputCodecContext);
        outputCodecContext = nullptr;
    }
    encoderSetupDone = false;
}
