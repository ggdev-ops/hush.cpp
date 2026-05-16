#ifndef TTS_ENGINE_H
#define TTS_ENGINE_H

#include <string>
#include <vector>
#include <memory>
#include <onnxruntime_cxx_api.h>

/**
 * Configuration structure
 */
struct TtsConfig {
    struct AEConfig {
        int sample_rate;
        int base_chunk_size;
    } ae;
    
    struct TTLConfig {
        int chunk_compress_factor;
        int latent_dim;
    } ttl;
};

/**
 * Unicode text processor
 */
class UnicodeProcessor {
public:
    explicit UnicodeProcessor(const std::string& unicode_indexer_json_path);

    // Process text list to text IDs and mask
    void call(
        const std::vector<std::string>& text_list,
        const std::vector<std::string>& lang_list,
        std::vector<std::vector<int64_t>>& text_ids,
        std::vector<std::vector<std::vector<float>>>& text_mask
    );

private:
    std::vector<int64_t> indexer_;
    
    std::string preprocessText(const std::string& text, const std::string& lang);
    std::vector<uint16_t> textToUnicodeValues(const std::string& text);
    std::vector<std::vector<std::vector<float>>> getTextMask(
        const std::vector<int64_t>& text_ids_lengths
    );
};

/**
 * Style class
 */
class TtsStyle {
public:
    TtsStyle(const std::vector<float>& ttl_data, const std::vector<int64_t>& ttl_shape,
             const std::vector<float>& dp_data, const std::vector<int64_t>& dp_shape);
    
    const std::vector<float>& getTtlData() const { return ttl_data_; }
    const std::vector<float>& getDpData() const { return dp_data_; }
    const std::vector<int64_t>& getTtlShape() const { return ttl_shape_; }
    const std::vector<int64_t>& getDpShape() const { return dp_shape_; }

private:
    std::vector<float> ttl_data_;
    std::vector<float> dp_data_;
    std::vector<int64_t> ttl_shape_;
    std::vector<int64_t> dp_shape_;
};

/**
 * TtsEngine class
 */
class TtsEngine {
public:
    TtsEngine(
        const TtsConfig& cfgs,
        UnicodeProcessor* text_processor,
        Ort::Session* dp_ort,
        Ort::Session* text_enc_ort,
        Ort::Session* vector_est_ort,
        Ort::Session* vocoder_ort
    );
    
    struct SynthesisResult {
        std::vector<float> wav;
        std::vector<float> duration;
    };
    
    SynthesisResult synthesize(
        Ort::MemoryInfo& memory_info,
        const std::string& text,
        const std::string& lang,
        const TtsStyle& style,
        int total_step,
        float speed = 1.05f
    );
    
    int getSampleRate() const { return sample_rate_; }

private:
    TtsConfig cfgs_;
    UnicodeProcessor* text_processor_;
    Ort::Session* dp_ort_;
    Ort::Session* text_enc_ort_;
    Ort::Session* vector_est_ort_;
    Ort::Session* vocoder_ort_;
    int sample_rate_;
    int base_chunk_size_;
    int chunk_compress_factor_;
    int ldim_;
    
    void sampleNoisyLatent(
        const std::vector<float>& duration,
        std::vector<std::vector<std::vector<float>>>& noisy_latent,
        std::vector<std::vector<std::vector<float>>>& latent_mask
    );
};

// ONNX model loading
struct OnnxModels {
    std::unique_ptr<Ort::Session> dp;
    std::unique_ptr<Ort::Session> text_enc;
    std::unique_ptr<Ort::Session> vector_est;
    std::unique_ptr<Ort::Session> vocoder;
};

// Loader functions
std::unique_ptr<TtsEngine> loadTtsEngine(
    Ort::Env& env,
    const std::string& onnx_dir,
    bool use_gpu = false
);

TtsStyle loadTtsStyle(const std::vector<std::string>& voice_style_paths, bool verbose = false);

// Utility functions
void writeWav(const std::string& filename, const std::vector<float>& audio_data, int sample_rate);
std::vector<std::string> chunkTtsText(const std::string& text, int max_len = 300);

#endif // TTS_ENGINE_H
