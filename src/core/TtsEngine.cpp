#include "core/TtsEngine.h"
#include "core/Logger.h"
#include <fstream>
#include <cmath>
#include <algorithm>
#include <random>
#include <sstream>
#include <regex>
#include <unordered_map>
#include <nlohmann/json.hpp>

using json = nlohmann::json;

// Available languages for multilingual TTS
const std::vector<std::string> AVAILABLE_LANGS = {"en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"};

// Global tensor buffers for memory management
static std::vector<std::vector<float>> g_tensor_buffers_float;
static std::vector<std::vector<int64_t>> g_tensor_buffers_int64;

void clearTensorBuffers() {
    g_tensor_buffers_float.clear();
    g_tensor_buffers_int64.clear();
}

static std::string trim(const std::string& str) {
    size_t start = 0;
    while (start < str.size() && std::isspace(static_cast<unsigned char>(str[start]))) {
        start++;
    }
    size_t end = str.size();
    while (end > start && std::isspace(static_cast<unsigned char>(str[end - 1]))) {
        end--;
    }
    return str.substr(start, end - start);
}

// Tensor conversion utilities
static Ort::Value arrayToTensor(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::vector<std::vector<float>>>& array,
    const std::vector<int64_t>& dims
) {
    std::vector<float> flat;
    for (const auto& batch : array) {
        for (const auto& row : batch) {
            for (float val : row) {
                flat.push_back(val);
            }
        }
    }
    g_tensor_buffers_float.push_back(std::move(flat));
    auto& buffer = g_tensor_buffers_float.back();
    return Ort::Value::CreateTensor<float>(memory_info, buffer.data(), buffer.size(), dims.data(), dims.size());
}

static Ort::Value intArrayToTensor(
    Ort::MemoryInfo& memory_info,
    const std::vector<std::vector<int64_t>>& array,
    const std::vector<int64_t>& dims
) {
    std::vector<int64_t> flat;
    for (const auto& row : array) {
        for (int64_t val : row) {
            flat.push_back(val);
        }
    }
    g_tensor_buffers_int64.push_back(std::move(flat));
    auto& buffer = g_tensor_buffers_int64.back();
    return Ort::Value::CreateTensor<int64_t>(memory_info, buffer.data(), buffer.size(), dims.data(), dims.size());
}

static std::vector<std::vector<std::vector<float>>> lengthToMask(const std::vector<int64_t>& lengths, int max_len = -1) {
    if (max_len == -1) {
        max_len = *std::max_element(lengths.begin(), lengths.end());
    }
    std::vector<std::vector<std::vector<float>>> mask;
    for (auto len : lengths) {
        std::vector<std::vector<float>> batch_mask(1);
        batch_mask[0].resize(max_len);
        for (int i = 0; i < max_len; i++) {
            batch_mask[0][i] = (i < len) ? 1.0f : 0.0f;
        }
        mask.push_back(batch_mask);
    }
    return mask;
}

static std::vector<std::vector<std::vector<float>>> getLatentMask(const std::vector<int64_t>& wav_lengths, int base_chunk_size, int chunk_compress_factor) {
    int latent_size = base_chunk_size * chunk_compress_factor;
    std::vector<int64_t> latent_lengths;
    for (auto len : wav_lengths) {
        latent_lengths.push_back((len + latent_size - 1) / latent_size);
    }
    return lengthToMask(latent_lengths);
}

// UnicodeProcessor Implementation
UnicodeProcessor::UnicodeProcessor(const std::string& unicode_indexer_json_path) {
    std::ifstream file(unicode_indexer_json_path);
    if (!file.is_open()) throw std::runtime_error("Failed to open: " + unicode_indexer_json_path);
    json j; file >> j;
    indexer_ = j.get<std::vector<int64_t>>();
}

std::string UnicodeProcessor::preprocessText(const std::string& text, const std::string& lang) {
    std::string result = text;
    struct Replacement { const char* from; const char* to; };
    const Replacement replacements[] = {
        {"–", "-"}, {"‑", "-"}, {"—", "-"}, {"_", " "}, { u8"\u201C", "\"" }, { u8"\u201D", "\"" },
        { u8"\u2018", "'" }, { u8"\u2019", "'" }, {"´", "'"}, {"`", "'"}, {"[", " "}, {"]", " "},
        {"|", " "}, {"/", " "}, {"#", " "}, {"→", " "}, {"←", " "}
    };
    for (const auto& repl : replacements) {
        size_t pos = 0;
        while ((pos = result.find(repl.from, pos)) != std::string::npos) {
            result.replace(pos, strlen(repl.from), repl.to);
            pos += strlen(repl.to);
        }
    }
    result = std::regex_replace(result, std::regex("[\xF0][\x9F][\x80-\xBF][\x80-\xBF]"), "");
    const char* special_symbols[] = {"♥", "☆", "♡", "©", "\\"};
    for (const char* symbol : special_symbols) {
        size_t pos = 0;
        while ((pos = result.find(symbol, pos)) != std::string::npos) { result.erase(pos, strlen(symbol)); }
    }
    const Replacement expr_replacements[] = {{"@", " at "}, {"e.g.,", "for example, "}, {"i.e.,", "that is, "}};
    for (const auto& repl : expr_replacements) {
        size_t pos = 0;
        while ((pos = result.find(repl.from, pos)) != std::string::npos) {
            result.replace(pos, strlen(repl.from), repl.to);
            pos += strlen(repl.to);
        }
    }
    result = std::regex_replace(result, std::regex("\\s+"), " ");
    result = trim(result);
    if (!result.empty()) {
        char last_char = result.back();
        bool ends_with_punct = (last_char == '.' || last_char == '!' || last_char == '?' || last_char == ';' || last_char == ':' || last_char == ',');
        if (!ends_with_punct) result += ".";
    }
    result = "<" + lang + ">" + result + "</" + lang + ">";
    return result;
}

static void decomposeCharacter(uint32_t codepoint, std::vector<uint16_t>& output) {
    if (codepoint >= 0xAC00 && codepoint < 0xAC00 + 11172) {
        uint32_t sIndex = codepoint - 0xAC00;
        output.push_back(static_cast<uint16_t>(0x1100 + (sIndex / 588)));
        output.push_back(static_cast<uint16_t>(0x1161 + ((sIndex % 588) / 28)));
        uint32_t tIndex = sIndex % 28;
        if (tIndex > 0) output.push_back(static_cast<uint16_t>(0x11A7 + tIndex));
        return;
    }
    output.push_back(static_cast<uint16_t>(codepoint & 0xFFFF));
}

std::vector<uint16_t> UnicodeProcessor::textToUnicodeValues(const std::string& text) {
    std::vector<uint16_t> unicode_values;
    size_t i = 0;
    while (i < text.size()) {
        uint32_t codepoint = 0;
        unsigned char c = static_cast<unsigned char>(text[i]);
        if ((c & 0x80) == 0) { codepoint = c; i += 1; }
        else if ((c & 0xE0) == 0xC0 && i + 1 < text.size()) { codepoint = ((c & 0x1F) << 6) | (static_cast<unsigned char>(text[i+1]) & 0x3F); i += 2; }
        else if ((c & 0xF0) == 0xE0 && i + 2 < text.size()) { codepoint = ((c & 0x0F) << 12) | ((static_cast<unsigned char>(text[i+1]) & 0x3F) << 6) | (static_cast<unsigned char>(text[i+2]) & 0x3F); i += 3; }
        else if ((c & 0xF8) == 0xF0 && i + 3 < text.size()) { codepoint = ((c & 0x07) << 18) | ((static_cast<unsigned char>(text[i+1]) & 0x3F) << 12) | ((static_cast<unsigned char>(text[i+2]) & 0x3F) << 6) | (static_cast<unsigned char>(text[i+3]) & 0x3F); i += 4; }
        else { i++; continue; }
        decomposeCharacter(codepoint, unicode_values);
    }
    return unicode_values;
}

void UnicodeProcessor::call(const std::vector<std::string>& text_list, const std::vector<std::string>& lang_list, std::vector<std::vector<int64_t>>& text_ids, std::vector<std::vector<std::vector<float>>>& text_mask) {
    std::vector<int64_t> text_ids_lengths;
    std::vector<std::vector<uint16_t>> all_unicode_vals;
    for (size_t i = 0; i < text_list.size(); i++) {
        auto unicode_vals = textToUnicodeValues(preprocessText(text_list[i], lang_list[i]));
        text_ids_lengths.push_back(unicode_vals.size());
        all_unicode_vals.push_back(std::move(unicode_vals));
    }
    int64_t max_len = *std::max_element(text_ids_lengths.begin(), text_ids_lengths.end());
    text_ids.resize(text_list.size());
    for (size_t i = 0; i < all_unicode_vals.size(); i++) {
        text_ids[i].resize(max_len, 0);
        for (size_t j = 0; j < all_unicode_vals[i].size(); j++) {
            if (all_unicode_vals[i][j] < indexer_.size()) text_ids[i][j] = indexer_[all_unicode_vals[i][j]];
        }
    }
    text_mask = lengthToMask(text_ids_lengths);
}

// TtsStyle Implementation
TtsStyle::TtsStyle(const std::vector<float>& ttl_data, const std::vector<int64_t>& ttl_shape, const std::vector<float>& dp_data, const std::vector<int64_t>& dp_shape)
    : ttl_data_(ttl_data), ttl_shape_(ttl_shape), dp_data_(dp_data), dp_shape_(dp_shape) {}

// TtsEngine Implementation
TtsEngine::TtsEngine(const TtsConfig& cfgs, UnicodeProcessor* text_processor, Ort::Session* dp_ort, Ort::Session* text_enc_ort, Ort::Session* vector_est_ort, Ort::Session* vocoder_ort)
    : cfgs_(cfgs), text_processor_(text_processor), dp_ort_(dp_ort), text_enc_ort_(text_enc_ort), vector_est_ort_(vector_est_ort), vocoder_ort_(vocoder_ort) {
    sample_rate_ = cfgs.ae.sample_rate;
    base_chunk_size_ = cfgs.ae.base_chunk_size;
    chunk_compress_factor_ = cfgs.ttl.chunk_compress_factor;
    ldim_ = cfgs.ttl.latent_dim;
}

void TtsEngine::sampleNoisyLatent(const std::vector<float>& duration, std::vector<std::vector<std::vector<float>>>& noisy_latent, std::vector<std::vector<std::vector<float>>>& latent_mask) {
    int bsz = duration.size();
    float wav_len_max = *std::max_element(duration.begin(), duration.end()) * sample_rate_;
    std::vector<int64_t> wav_lengths;
    for (float d : duration) wav_lengths.push_back(static_cast<int64_t>(d * sample_rate_));
    int chunk_size = base_chunk_size_ * chunk_compress_factor_;
    int latent_len = static_cast<int>((wav_len_max + chunk_size - 1) / chunk_size);
    int latent_dim = ldim_ * chunk_compress_factor_;
    std::random_device rd; std::mt19937 gen(rd()); std::normal_distribution<float> dist(0.0f, 1.0f);
    noisy_latent.resize(bsz);
    for (int b = 0; b < bsz; b++) {
        noisy_latent[b].resize(latent_dim);
        for (int d = 0; d < latent_dim; d++) {
            noisy_latent[b][d].resize(latent_len);
            for (int t = 0; t < latent_len; t++) noisy_latent[b][d][t] = dist(gen);
        }
    }
    latent_mask = getLatentMask(wav_lengths, base_chunk_size_, chunk_compress_factor_);
}

TtsEngine::SynthesisResult TtsEngine::synthesize(Ort::MemoryInfo& memory_info, const std::string& text, const std::string& lang, const TtsStyle& style, int total_step, float speed) {
    int bsz = 1;
    std::vector<std::vector<int64_t>> text_ids;
    std::vector<std::vector<std::vector<float>>> text_mask;
    text_processor_->call({text}, {lang}, text_ids, text_mask);
    
    std::vector<int64_t> text_ids_shape = {bsz, static_cast<int64_t>(text_ids[0].size())};
    std::vector<int64_t> text_mask_shape = {bsz, 1, static_cast<int64_t>(text_mask[0][0].size())};
    
    auto text_ids_tensor = intArrayToTensor(memory_info, text_ids, text_ids_shape);
    auto text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
    auto style_ttl_tensor = Ort::Value::CreateTensor<float>(memory_info, const_cast<float*>(style.getTtlData().data()), style.getTtlData().size(), style.getTtlShape().data(), style.getTtlShape().size());
    auto style_dp_tensor = Ort::Value::CreateTensor<float>(memory_info, const_cast<float*>(style.getDpData().data()), style.getDpData().size(), style.getDpShape().data(), style.getDpShape().size());

    const char* dp_in[] = {"text_ids", "style_dp", "text_mask"};
    const char* dp_out[] = {"duration"};
    std::vector<Ort::Value> dp_inputs;
    dp_inputs.push_back(std::move(text_ids_tensor));
    dp_inputs.push_back(std::move(style_dp_tensor));
    dp_inputs.push_back(std::move(text_mask_tensor));
    auto dp_outputs = dp_ort_->Run(Ort::RunOptions{nullptr}, dp_in, dp_inputs.data(), dp_inputs.size(), dp_out, 1);
    float duration = dp_outputs[0].GetTensorMutableData<float>()[0] / speed;

    text_ids_tensor = intArrayToTensor(memory_info, text_ids, text_ids_shape);
    text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
    style_ttl_tensor = Ort::Value::CreateTensor<float>(memory_info, const_cast<float*>(style.getTtlData().data()), style.getTtlData().size(), style.getTtlShape().data(), style.getTtlShape().size());
    const char* te_in[] = {"text_ids", "style_ttl", "text_mask"};
    const char* te_out[] = {"text_emb"};
    std::vector<Ort::Value> te_inputs;
    te_inputs.push_back(std::move(text_ids_tensor));
    te_inputs.push_back(std::move(style_ttl_tensor));
    te_inputs.push_back(std::move(text_mask_tensor));
    auto te_outputs = text_enc_ort_->Run(Ort::RunOptions{nullptr}, te_in, te_inputs.data(), te_inputs.size(), te_out, 1);
    
    auto text_emb_info = te_outputs[0].GetTensorTypeAndShapeInfo();
    std::vector<float> text_emb_vec(te_outputs[0].GetTensorMutableData<float>(), te_outputs[0].GetTensorMutableData<float>() + text_emb_info.GetElementCount());
    auto text_emb_shape = text_emb_info.GetShape();

    std::vector<std::vector<std::vector<float>>> xt, latent_mask;
    sampleNoisyLatent({duration}, xt, latent_mask);
    std::vector<int64_t> latent_shape = {bsz, static_cast<int64_t>(xt[0].size()), static_cast<int64_t>(xt[0][0].size())};
    std::vector<int64_t> lmask_shape = {bsz, 1, static_cast<int64_t>(latent_mask[0][0].size())};

    for (int step = 0; step < total_step; step++) {
        std::vector<float> t_step = {static_cast<float>(step)};
        std::vector<float> tt_step = {static_cast<float>(total_step)};
        text_mask_tensor = arrayToTensor(memory_info, text_mask, text_mask_shape);
        auto lmask_tensor = arrayToTensor(memory_info, latent_mask, lmask_shape);
        auto xt_tensor = arrayToTensor(memory_info, xt, latent_shape);
        style_ttl_tensor = Ort::Value::CreateTensor<float>(memory_info, const_cast<float*>(style.getTtlData().data()), style.getTtlData().size(), style.getTtlShape().data(), style.getTtlShape().size());
        auto te_tensor = Ort::Value::CreateTensor<float>(memory_info, text_emb_vec.data(), text_emb_vec.size(), text_emb_shape.data(), text_emb_shape.size());
        auto t_tensor = Ort::Value::CreateTensor<float>(memory_info, t_step.data(), 1, std::vector<int64_t>{1}.data(), 1);
        auto tt_tensor = Ort::Value::CreateTensor<float>(memory_info, tt_step.data(), 1, std::vector<int64_t>{1}.data(), 1);

        const char* ve_in[] = {"noisy_latent", "text_emb", "style_ttl", "text_mask", "latent_mask", "total_step", "current_step"};
        const char* ve_out[] = {"denoised_latent"};
        std::vector<Ort::Value> ve_inputs;
        ve_inputs.push_back(std::move(xt_tensor)); ve_inputs.push_back(std::move(te_tensor));
        ve_inputs.push_back(std::move(style_ttl_tensor)); ve_inputs.push_back(std::move(text_mask_tensor));
        ve_inputs.push_back(std::move(lmask_tensor)); ve_inputs.push_back(std::move(tt_tensor));
        ve_inputs.push_back(std::move(t_tensor));
        auto ve_outputs = vector_est_ort_->Run(Ort::RunOptions{nullptr}, ve_in, ve_inputs.data(), ve_inputs.size(), ve_out, 1);
        float* d_data = ve_outputs[0].GetTensorMutableData<float>();
        size_t idx = 0;
        for (size_t d = 0; d < xt[0].size(); d++) for (size_t t = 0; t < xt[0][d].size(); t++) xt[0][d][t] = d_data[idx++];
    }

    auto l_tensor = arrayToTensor(memory_info, xt, latent_shape);
    const char* vo_in[] = {"latent"}; const char* vo_out[] = {"wav_tts"};
    std::vector<Ort::Value> vo_inputs; vo_inputs.push_back(std::move(l_tensor));
    auto vo_outputs = vocoder_ort_->Run(Ort::RunOptions{nullptr}, vo_in, vo_inputs.data(), vo_inputs.size(), vo_out, 1);
    auto wav_info = vo_outputs[0].GetTensorTypeAndShapeInfo();
    
    SynthesisResult res;
    res.wav.assign(vo_outputs[0].GetTensorMutableData<float>(), vo_outputs[0].GetTensorMutableData<float>() + wav_info.GetElementCount());
    res.duration = {duration};
    clearTensorBuffers();
    return res;
}

// Loader and Utilities
std::unique_ptr<TtsEngine> loadTtsEngine(Ort::Env& env, const std::string& onnx_dir, bool use_gpu) {
    Logger::info("Loading TTS Engine from: %s", onnx_dir.c_str());
    std::string cfg_path = onnx_dir + "/tts.json";
    std::ifstream f(cfg_path); if (!f.is_open()) throw std::runtime_error("No config");
    json j; f >> j;
    TtsConfig cfg;
    cfg.ae.sample_rate = j["ae"]["sample_rate"]; cfg.ae.base_chunk_size = j["ae"]["base_chunk_size"];
    cfg.ttl.chunk_compress_factor = j["ttl"]["chunk_compress_factor"]; cfg.ttl.latent_dim = j["ttl"]["latent_dim"];
    
    Ort::SessionOptions opts;
    static OnnxModels m;
    m.dp = std::make_unique<Ort::Session>(env, (onnx_dir + "/duration_predictor.onnx").c_str(), opts);
    m.text_enc = std::make_unique<Ort::Session>(env, (onnx_dir + "/text_encoder.onnx").c_str(), opts);
    m.vector_est = std::make_unique<Ort::Session>(env, (onnx_dir + "/vector_estimator.onnx").c_str(), opts);
    m.vocoder = std::make_unique<Ort::Session>(env, (onnx_dir + "/vocoder.onnx").c_str(), opts);
    
    static std::unique_ptr<UnicodeProcessor> up = std::make_unique<UnicodeProcessor>(onnx_dir + "/unicode_indexer.json");
    return std::make_unique<TtsEngine>(cfg, up.get(), m.dp.get(), m.text_enc.get(), m.vector_est.get(), m.vocoder.get());
}

TtsStyle loadTtsStyle(const std::vector<std::string>& voice_style_paths, bool verbose) {
    std::ifstream f(voice_style_paths[0]); json j; f >> j;
    auto ttl_dims = j["style_ttl"]["dims"].get<std::vector<int64_t>>();
    auto dp_dims = j["style_dp"]["dims"].get<std::vector<int64_t>>();
    auto ttl_data_nested = j["style_ttl"]["data"].get<std::vector<std::vector<std::vector<float>>>>();
    auto dp_data_nested = j["style_dp"]["data"].get<std::vector<std::vector<std::vector<float>>>>();
    std::vector<float> ttl_flat, dp_flat;
    for (const auto& b : ttl_data_nested) for (const auto& r : b) ttl_flat.insert(ttl_flat.end(), r.begin(), r.end());
    for (const auto& b : dp_data_nested) for (const auto& r : b) dp_flat.insert(dp_flat.end(), r.begin(), r.end());
    return TtsStyle(ttl_flat, ttl_dims, dp_flat, dp_dims);
}

void writeWav(const std::string& filename, const std::vector<float>& audio_data, int sample_rate) {
    std::ofstream file(filename, std::ios::binary);
    int32_t data_size = audio_data.size() * 2;
    int32_t chunk_size = 36 + data_size;
    file.write("RIFF", 4); file.write((char*)&chunk_size, 4); file.write("WAVE", 4);
    file.write("fmt ", 4); int32_t fs = 16; file.write((char*)&fs, 4);
    int16_t fmt = 1, chan = 1; file.write((char*)&fmt, 2); file.write((char*)&chan, 2);
    file.write((char*)&sample_rate, 4); int32_t br = sample_rate * 2; file.write((char*)&br, 4);
    int16_t ba = 2, bps = 16; file.write((char*)&ba, 2); file.write((char*)&bps, 2);
    file.write("data", 4); file.write((char*)&data_size, 4);
    for (float s : audio_data) {
        int16_t is = static_cast<int16_t>(std::max(-1.0f, std::min(1.0f, s)) * 32767);
        file.write((char*)&is, 2);
    }
}

std::vector<std::string> chunkTtsText(const std::string& text, int max_len) {
    std::vector<std::string> chunks;
    std::regex sent_regex(R"([.!?]\s+)");
    std::sregex_token_iterator iter(text.begin(), text.end(), sent_regex, -1), end;
    std::string current = "";
    for (; iter != end; ++iter) {
        std::string s = *iter;
        if (current.length() + s.length() < (size_t)max_len) current += s + " ";
        else { if (!current.empty()) chunks.push_back(trim(current)); current = s; }
    }
    if (!current.empty()) chunks.push_back(trim(current));
    if (chunks.empty()) chunks.push_back(text);
    return chunks;
}
