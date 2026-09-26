import SwiftUI
import UniformTypeIdentifiers
import AVFoundation

// MARK: - HushAggression Preset

enum HushAggression: Int, CaseIterable, Identifiable {
    case slightly = 0
    case gentle
    case `default`
    case aggressive
    case moreAggressive

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .slightly: return "Slightly"
        case .gentle: return "Gentle"
        case .default: return "Default"
        case .aggressive: return "Aggressive"
        case .moreAggressive: return "More Aggressive"
        }
    }

    var thresholdDb: Double {
        switch self {
        case .slightly: return -45.0
        case .gentle: return -35.0
        case .default: return -30.0
        case .aggressive: return -25.0
        case .moreAggressive: return -20.0
        }
    }

    var aggression: Double {
        switch self {
        case .slightly: return 0.2
        case .gentle: return 0.4
        case .default: return 0.5
        case .aggressive: return 0.8
        case .moreAggressive: return 0.95
        }
    }

    var params: String {
        String(format: "%.0f dB / %.1f", thresholdDb, aggression)
    }
}

// MARK: - Tab

enum HushTab: Int, CaseIterable, Identifiable {
    case singleFile = 0
    case batch
    case recorder

    var id: Int { rawValue }

    var label: String {
        switch self {
        case .singleFile: return "Single File"
        case .batch: return "Batch Directory"
        case .recorder: return "Recorder"
        }
    }
}

// MARK: - Player Mode

enum PlayerMode {
    case original
    case processed
}

// MARK: - Batch aggregate stats (persisted for results card)

struct BatchStats {
    var totalInput: Int64 = 0
    var totalOutput: Int64 = 0
    var totalRemoved: Int64 = 0
    var silentSegments: Int32 = 0
    var processed: Int = 0
    var total: Int = 0
    var failures: [String] = []

    var reduction: Double {
        totalInput > 0 ? (Double(totalRemoved) / Double(totalInput)) * 100.0 : 0.0
    }
}

// MARK: - Theme

enum HushTheme {
    static let bg = Color.black
    static let card = Color(red: 0.08, green: 0.08, blue: 0.08)
    static let cardBorder = Color(red: 0.2, green: 0.2, blue: 0.2)
    static let accent = Color(red: 0.0, green: 0.94, blue: 1.0)
    static let text = Color.white
    static let textDim = Color.gray
}

// MARK: - ContentView

struct ContentView: View {
    @State private var selectedTab: HushTab = .singleFile
    @State private var aggression: HushAggression = .default

    // Engine
    @State private var hush: Hush?

    // Status console (shared across tabs)
    @State private var statusText = "Hush Engine Ready. Select a .wav file to process."

    // Single File state (isolated)
    @State private var sfFileURL: URL?
    @State private var sfDecoded: DecodedAudio?
    @State private var sfProcessed: [Int16]?
    @State private var sfStats: HushStats?
    @State private var sfProcessingTime: Double = 0
    @State private var sfIsProcessing = false
    @State private var sfTempWavURL: URL?

    // Player state (shared transport)
    @State private var player: AudioPlayer?
    @State private var playerMode: PlayerMode = .original
    @State private var isPlaying = false
    @State private var isPaused = false
    @State private var playbackTimer: Timer?
    @State private var playbackDuration: Double = 0
    @State private var playbackPosition: Double = 0
    /// Temp copy currently under the playhead — protected from pruneTempFiles.
    @State private var playbackFileURL: URL?

    // File picker
    @State private var showFilePicker = false
    @State private var showSavePicker = false

    // Batch state
    @State private var inputDirectoryURL: URL?
    @State private var outputDirectoryURL: URL?
    @State private var inputDirAccessHeld = false
    @State private var inputPlaylistFiles: [URL] = []
    @State private var outputPlaylistFiles: [URL] = []
    @State private var currentTrackIndex: Int = 0
    @State private var isBatchProcessing = false
    @State private var batchProcessedCount: Int = 0
    @State private var batchTotalCount: Int = 0
    @State private var batchStats: BatchStats?
    @State private var showInputDirPicker = false
    @State private var showOutputDirPicker = false

    // Recorder state (isolated)
    @State private var recorder: AudioRecorder?
    @State private var isRecording = false
    @State private var hasMicPermission = false
    @State private var useSilenceRemoval = true
    @State private var recordDbLevel: Double = -100
    @State private var recordPressure: Int32 = 0
    @State private var recordDegradation: Int32 = 0
    @State private var recordStats: HushStats?
    @State private var recorderPollTimer: Timer?
    @State private var recFileURL: URL?
    @State private var recDecoded: DecodedAudio?
    @State private var recProcessed: [Int16]?
    @State private var recTempWavURL: URL?
    @State private var recNeedsHush = false

    /// True while any long-running native work is in flight — gates UI that could corrupt it (C2/C3).
    private var isBusy: Bool {
        sfIsProcessing || isBatchProcessing || isRecording
    }

    /// Batch playlist follows the Original/Processed toggle (P1 parity with Android).
    private var playlistFiles: [URL] {
        playerMode == .processed ? outputPlaylistFiles : inputPlaylistFiles
    }

    private var processedStats: HushStats? {
        guard selectedTab == .singleFile, sfProcessed != nil else { return nil }
        return sfStats
    }

    private var exportableSamples: [Int16] {
        switch selectedTab {
        case .singleFile: return sfProcessed ?? []
        case .batch: return []
        case .recorder: return recProcessed ?? []
        }
    }

    private var exportableSampleRate: Int32 {
        switch selectedTab {
        case .singleFile: return sfDecoded?.sampleRate ?? 16000
        case .batch: return 16000
        case .recorder: return recDecoded?.sampleRate ?? 16000
        }
    }

    var body: some View {
        ZStack {
            HushTheme.bg.ignoresSafeArea()

            VStack(spacing: 0) {
                headerBar
                tabSelector
                ScrollView {
                    VStack(spacing: 16) {
                        aggressionCard
                        statusConsoleCard
                        audioPlayerCard
                        singleFileActions
                        batchDirectoryActions
                        recorderActions
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            configureAudioSession()
            ensureEngine()
            checkMicPermission()
        }
        .onDisappear { cleanup() }
        .fileImporter(
            isPresented: $showFilePicker,
            allowedContentTypes: [.wav, .mp3, .audio],
            allowsMultipleSelection: false
        ) { result in
            handleFileImport(result)
        }
        .fileExporter(
            isPresented: $showSavePicker,
            document: WavDocument(samples: exportableSamples, sampleRate: exportableSampleRate),
            contentType: .wav,
            defaultFilename: "hush_processed"
        ) { result in
            switch result {
            case .success: statusText = "File saved successfully."
            case .failure(let error): statusText = "Save failed: \(error.localizedDescription)"
            }
        }
        .sheet(isPresented: $showInputDirPicker) {
            DirectoryPicker(isPresented: $showInputDirPicker) { url in
                handleInputDirectory(url)
            }
        }
        .sheet(isPresented: $showOutputDirPicker) {
            DirectoryPicker(isPresented: $showOutputDirPicker) { url in
                handleOutputDirectory(url)
            }
        }
    }

    // MARK: - Header

    private var headerBar: some View {
        VStack(spacing: 2) {
            Text("HUSH ENGINE")
                .font(.system(size: 20, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.accent)
            Text("iOS — Pure Swift, No JNI")
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundColor(HushTheme.textDim)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    // MARK: - Tab Selector

    private var tabSelector: some View {
        HStack(spacing: 0) {
            ForEach(HushTab.allCases) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    Text(tab.label)
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(selectedTab == tab ? HushTheme.bg : HushTheme.textDim)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(selectedTab == tab ? HushTheme.accent : Color.clear)
                }
                .disabled(isBusy)
            }
        }
        .background(HushTheme.card)
        .cornerRadius(8)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
    }

    // MARK: - Aggression Level Card

    private var aggressionCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("HUSH AGGRESSION LEVEL")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            Menu {
                ForEach(HushAggression.allCases) { preset in
                    Button {
                        aggression = preset
                        recreateEngineIfPossible()
                    } label: {
                        HStack {
                            Text(preset.label)
                            if aggression == preset {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(aggression.label)
                            .font(.system(size: 15, weight: .semibold, design: .monospaced))
                            .foregroundColor(HushTheme.accent)
                        Text(aggression.params)
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundColor(HushTheme.textDim)
                    }
                    Spacer()
                    Image(systemName: "chevron.down")
                        .foregroundColor(HushTheme.textDim)
                        .font(.system(size: 12))
                }
                .padding(12)
                .background(HushTheme.card)
                .cornerRadius(8)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(HushTheme.cardBorder, lineWidth: 1)
                )
            }
            .disabled(isBusy)
        }
        .padding(12)
        .background(HushTheme.card)
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(HushTheme.cardBorder, lineWidth: 1)
        )
    }

    // MARK: - Status Console Card

    private var statusConsoleCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("STATUS")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            ScrollView(.vertical, showsIndicators: false) {
                Text(statusText)
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundColor(HushTheme.accent)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 60, maxHeight: 100)
        }
        .padding(12)
        .background(Color.black)
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(HushTheme.cardBorder, lineWidth: 1)
        )
    }

    // MARK: - Audio Player Card

    private var audioPlayerCard: some View {
        VStack(spacing: 10) {
            Text("AUDIO PLAYER")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)
                .frame(maxWidth: .infinity, alignment: .leading)

            // Original / Processed toggle
            HStack(spacing: 0) {
                Button {
                    playerMode = .original
                    stopPlayback()
                    currentTrackIndex = 0
                } label: {
                    Text("Original")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(playerMode == .original ? HushTheme.bg : HushTheme.textDim)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(playerMode == .original ? HushTheme.accent : Color.clear)
                }
                .disabled(!isModeAvailable(.original))
                Button {
                    playerMode = .processed
                    stopPlayback()
                    currentTrackIndex = 0
                } label: {
                    Text("Processed")
                        .font(.system(size: 13, weight: .semibold, design: .monospaced))
                        .foregroundColor(playerMode == .processed ? HushTheme.bg : HushTheme.textDim)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(playerMode == .processed ? HushTheme.accent : Color.clear)
                }
                .disabled(!isModeAvailable(.processed))
            }
            .background(HushTheme.card)
            .cornerRadius(6)

            // Player controls
            HStack(spacing: 20) {
                // Prev (batch mode)
                if selectedTab == .batch && !playlistFiles.isEmpty {
                    Button { playTrack(currentTrackIndex - 1) } label: {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 22))
                            .foregroundColor(currentTrackIndex > 0 ? HushTheme.accent : HushTheme.textDim)
                    }
                    .disabled(currentTrackIndex <= 0)
                }

                Button { togglePlayback() } label: {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 28))
                        .foregroundColor(canPlay ? HushTheme.accent : HushTheme.textDim)
                }
                .disabled(!canPlay)

                Button { stopPlayback() } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 28))
                        .foregroundColor(isPlaying || isPaused ? .red : HushTheme.textDim)
                }
                .disabled(!isPlaying && !isPaused)

                // Next (batch mode)
                if selectedTab == .batch && !playlistFiles.isEmpty {
                    Button { playTrack(currentTrackIndex + 1) } label: {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 22))
                            .foregroundColor(currentTrackIndex + 1 < playlistFiles.count ? HushTheme.accent : HushTheme.textDim)
                    }
                    .disabled(currentTrackIndex + 1 >= playlistFiles.count)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)

            // Progress bar (stays visible while paused — C1)
            if (isPlaying || isPaused) && playbackDuration > 0 {
                VStack(spacing: 4) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 2)
                                .fill(HushTheme.cardBorder)
                                .frame(height: 4)
                            RoundedRectangle(cornerRadius: 2)
                                .fill(HushTheme.accent)
                                .frame(width: geo.size.width * min(playbackPosition / playbackDuration, 1.0), height: 4)
                        }
                    }
                    .frame(height: 4)
                    HStack {
                        Text(formatTime(playbackPosition))
                        Spacer()
                        Text(formatTime(playbackDuration))
                    }
                    .font(.system(size: 10, weight: .medium, design: .monospaced))
                    .foregroundColor(HushTheme.textDim)
                }
            }

            // Track info (batch mode)
            if selectedTab == .batch && !playlistFiles.isEmpty {
                Text("\(currentTrackIndex + 1)/\(playlistFiles.count) — \(playlistFiles[currentTrackIndex].lastPathComponent)")
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundColor(HushTheme.textDim)
                    .lineLimit(1)
            } else {
                Text(playerPrompt)
                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                    .foregroundColor(HushTheme.textDim)
            }
        }
        .padding(12)
        .background(HushTheme.card)
        .cornerRadius(10)
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(HushTheme.cardBorder, lineWidth: 1)
        )
    }

    private func isModeAvailable(_ mode: PlayerMode) -> Bool {
        switch selectedTab {
        case .singleFile:
            return mode == .original ? sfFileURL != nil : sfProcessed != nil
        case .batch:
            return mode == .original ? !inputPlaylistFiles.isEmpty : !outputPlaylistFiles.isEmpty
        case .recorder:
            return mode == .original ? recFileURL != nil : recProcessed != nil
        }
    }

    private var canPlay: Bool {
        isModeAvailable(playerMode)
    }

    private var playerPrompt: String {
        switch selectedTab {
        case .singleFile:
            if playerMode == .original {
                return sfFileURL != nil ? "Ready to play original" : "Select a WAV file to start the player."
            } else {
                return sfProcessed != nil ? "Ready to play processed" : "Run Hush to generate processed audio."
            }
        case .batch:
            return playlistFiles.isEmpty
                ? (playerMode == .original
                    ? "Select an input directory to build a playlist."
                    : "Run a batch first — outputs will appear here.")
                : "Ready to play track"
        case .recorder:
            if recNeedsHush {
                return "Raw recording ready — playing Original, or tap Start Hush to process."
            }
            if playerMode == .original {
                return recFileURL != nil ? "Ready to play original recording" : "Record audio to start the player."
            } else {
                return recProcessed != nil ? "Ready to play processed" : "Tap Start Hush after recording to generate processed audio."
            }
        }
    }

    // MARK: - Single File Actions

    @ViewBuilder
    private var singleFileActions: some View {
        if selectedTab == .singleFile {
            VStack(spacing: 12) {
                if let url = sfFileURL {
                    HStack {
                        Image(systemName: "doc.audio")
                            .foregroundColor(HushTheme.accent)
                        Text(url.lastPathComponent)
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundColor(HushTheme.text)
                            .lineLimit(1)
                        Spacer()
                    }
                    .padding(10)
                    .background(Color.black)
                    .cornerRadius(8)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(HushTheme.cardBorder, lineWidth: 1))
                }

                if let stats = processedStats {
                    processedStatsCard(stats)
                }

                Button { showFilePicker = true } label: {
                    Label("Select Audio File (WAV/MP3)", systemImage: "folder")
                        .font(.system(size: 14, weight: .bold, design: .monospaced))
                        .foregroundColor(HushTheme.bg)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 12)
                        .background(HushTheme.accent)
                        .cornerRadius(10)
                }
                .disabled(isBusy)

                Button { processFile() } label: {
                    HStack {
                        if sfIsProcessing {
                            ProgressView().tint(HushTheme.bg)
                        } else {
                            Image(systemName: "waveform")
                        }
                        Text(sfIsProcessing ? "Processing..." : "Start Hush")
                    }
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundColor(sfFileURL != nil && !sfIsProcessing ? HushTheme.bg : HushTheme.textDim)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(sfFileURL != nil && !sfIsProcessing ? HushTheme.accent : HushTheme.card)
                    .cornerRadius(10)
                }
                .disabled(sfFileURL == nil || sfIsProcessing)

                if sfProcessed != nil {
                    Button { showSavePicker = true } label: {
                        Label("Save Processed File", systemImage: "square.and.arrow.down")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundColor(HushTheme.bg)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(HushTheme.accent)
                            .cornerRadius(10)
                    }
                }
            }
        }
    }

    // MARK: - Batch Directory Actions

    @ViewBuilder
    private var batchDirectoryActions: some View {
        if selectedTab == .batch {
            VStack(spacing: 12) {
                // Input directory
                VStack(alignment: .leading, spacing: 6) {
                    Text("INPUT DIRECTORY")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(HushTheme.textDim)
                    HStack {
                        Image(systemName: "folder.fill")
                            .foregroundColor(HushTheme.accent)
                        Text(inputDirectoryURL?.lastPathComponent ?? "Not Selected")
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundColor(inputDirectoryURL != nil ? HushTheme.text : HushTheme.textDim)
                            .lineLimit(1)
                        Spacer()
                        Button("Select") { showInputDirPicker = true }
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(HushTheme.accent)
                            .disabled(isBatchProcessing)
                    }
                }
                .padding(12)
                .background(Color.black)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))

                // Output directory
                VStack(alignment: .leading, spacing: 6) {
                    Text("OUTPUT DIRECTORY")
                        .font(.system(size: 11, weight: .bold, design: .monospaced))
                        .foregroundColor(HushTheme.textDim)
                    HStack {
                        Image(systemName: "folder.badge.gearshape")
                            .foregroundColor(HushTheme.accent)
                        Text(outputDirectoryURL?.lastPathComponent ?? "Not Selected")
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundColor(outputDirectoryURL != nil ? HushTheme.text : HushTheme.textDim)
                            .lineLimit(1)
                        Spacer()
                        Button("Select") { showOutputDirPicker = true }
                            .font(.system(size: 12, weight: .bold, design: .monospaced))
                            .foregroundColor(HushTheme.accent)
                            .disabled(isBatchProcessing)
                    }
                }
                .padding(12)
                .background(Color.black)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))

                // Batch results
                if batchStats != nil || batchProcessedCount > 0 {
                    batchResultsCard
                }

                // Start batch
                Button { startBatchProcess() } label: {
                    HStack {
                        if isBatchProcessing {
                            ProgressView().tint(HushTheme.bg)
                            Text("Processing \(batchProcessedCount)/\(batchTotalCount)...")
                        } else {
                            Image(systemName: "list.bullet.rectangle")
                            Text("Start Batch Process")
                        }
                    }
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundColor(batchCanProcess ? HushTheme.bg : HushTheme.textDim)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(batchCanProcess ? HushTheme.accent : HushTheme.card)
                    .cornerRadius(10)
                }
                .disabled(!batchCanProcess)

                // Playlist
                if !playlistFiles.isEmpty {
                    playlistCard
                }
            }
        }
    }

    private var batchCanProcess: Bool {
        inputDirectoryURL != nil && outputDirectoryURL != nil && !isBatchProcessing
    }

    private var batchResultsCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("BATCH RESULTS")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            if let s = batchStats {
                statRow("Files Processed", "\(s.processed)/\(s.total)")
                statRow("Total Input", "\(s.totalInput) samples")
                statRow("Total Output", "\(s.totalOutput) samples")
                statRow("Removed", "\(s.totalRemoved) samples")
                statRow("Reduction", String(format: "%.2f%%", s.reduction))
                statRow("Silent Segments", "\(s.silentSegments)")
                if !s.failures.isEmpty {
                    statRow("Failures", "\(s.failures.count)")
                    ForEach(Array(s.failures.enumerated()), id: \.offset) { _, failure in
                        Text("Failed: \(failure)")
                            .font(.system(size: 11, weight: .medium, design: .monospaced))
                            .foregroundColor(.red)
                            .lineLimit(2)
                    }
                }
            } else {
                statRow("Files Processed", "\(batchProcessedCount)/\(batchTotalCount)")
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black)
        .cornerRadius(10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
    }

    private var playlistCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("PLAYLIST")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 2) {
                    ForEach(Array(playlistFiles.enumerated()), id: \.offset) { index, url in
                        Button {
                            playTrack(index)
                        } label: {
                            HStack {
                                Text("\(index + 1).")
                                    .font(.system(size: 11, weight: .bold, design: .monospaced))
                                    .foregroundColor(HushTheme.textDim)
                                    .frame(width: 28, alignment: .trailing)
                                Text(url.lastPathComponent)
                                    .font(.system(size: 11, weight: .medium, design: .monospaced))
                                    .foregroundColor(index == currentTrackIndex ? HushTheme.accent : HushTheme.text)
                                    .lineLimit(1)
                                if index == currentTrackIndex && (isPlaying || isPaused) {
                                    Spacer()
                                    Text(isPlaying ? "PLAYING" : "PAUSED")
                                        .font(.system(size: 9, weight: .bold, design: .monospaced))
                                        .foregroundColor(HushTheme.accent)
                                }
                                Spacer()
                            }
                            .padding(.vertical, 4)
                            .padding(.horizontal, 8)
                            .background(index == currentTrackIndex ? Color(red: 0.0, green: 0.2, blue: 0.2) : Color.clear)
                            .cornerRadius(4)
                        }
                    }
                }
            }
            .frame(maxHeight: 150)
        }
        .padding(12)
        .background(HushTheme.card)
        .cornerRadius(10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
    }

    // MARK: - Recorder Actions

    @ViewBuilder
    private var recorderActions: some View {
        if selectedTab == .recorder {
            VStack(spacing: 12) {
                // Silence removal toggle
                HStack {
                    Text("Hush silent portions in real-time")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(HushTheme.text)
                    Spacer()
                    Toggle("", isOn: $useSilenceRemoval)
                        .tint(HushTheme.accent)
                        .labelsHidden()
                        .disabled(isRecording)
                }
                .padding(12)
                .background(HushTheme.card)
                .cornerRadius(10)
                .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))

                // VU + metrics only when detector is active (toggle ON)
                if useSilenceRemoval && (isRecording || recordStats != nil) {
                    vuMeterCard
                    recorderMetricsCard
                }

                if isRecording && !useSilenceRemoval {
                    Text("Recording raw audio — tap Stop, then Start Hush.")
                        .font(.system(size: 12, weight: .medium, design: .monospaced))
                        .foregroundColor(HushTheme.textDim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                        .background(Color.black)
                        .cornerRadius(10)
                        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
                }

                // Save processed recording
                if !isRecording && recProcessed != nil {
                    Button { showSavePicker = true } label: {
                        Label("Save Processed File", systemImage: "square.and.arrow.down")
                            .font(.system(size: 14, weight: .bold, design: .monospaced))
                            .foregroundColor(HushTheme.bg)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                            .background(HushTheme.accent)
                            .cornerRadius(10)
                    }
                }

                // Action button
                Button { recorderButtonTapped() } label: {
                    HStack {
                        Image(systemName: recorderButtonIcon)
                        Text(recorderButtonLabel)
                    }
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundColor(recorderButtonColor == HushTheme.accent ? HushTheme.bg : HushTheme.text)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(recorderButtonColor)
                    .cornerRadius(10)
                }
            }
        }
    }

    private var vuMeterCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("SIGNAL LEVEL")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            HStack {
                Text(String(format: "%.1f dB", recordDbLevel))
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundColor(recordDbLevel > -40 ? HushTheme.accent : HushTheme.textDim)
                Spacer()
            }

            GeometryReader { geo in
                let normalized = max(0, min(1, (recordDbLevel + 80) / 80))
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(HushTheme.cardBorder)
                        .frame(height: 10)
                    RoundedRectangle(cornerRadius: 3)
                        .fill(
                            LinearGradient(
                                gradient: Gradient(colors: [
                                    Color(red: 0.0, green: 0.69, blue: 1.0),
                                    HushTheme.accent,
                                    Color(red: 0.0, green: 1.0, blue: 0.53),
                                ]),
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        )
                        .frame(width: geo.size.width * normalized, height: 10)
                }
            }
            .frame(height: 10)
        }
        .padding(12)
        .background(HushTheme.card)
        .cornerRadius(10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
    }

    private var recorderMetricsCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("LIVE METRICS")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)

            if let stats = recordStats {
                let durationSec = Double(stats.totalInputSamples) / 16000.0
                let mins = Int(durationSec) / 60
                let secs = Int(durationSec) % 60
                statRow("Processed Duration", String(format: "%d:%02d", mins, secs))
                statRow("Reduction", String(format: "%.1f%%", stats.reductionPercentage))
                statRow("Silence Segments", "\(stats.silentSegmentsDetected)")
            }

            statRow("Buffer Pressure", "\(recordPressure)/100")
                .foregroundColor(recordPressure > 70 ? .red : HushTheme.text)

            HStack {
                Text("Degradation")
                    .font(.system(size: 12, weight: .medium, design: .monospaced))
                    .foregroundColor(HushTheme.textDim)
                Spacer()
                Text(degradationLabel)
                    .font(.system(size: 12, weight: .bold, design: .monospaced))
                    .foregroundColor(degradationColor)
            }
        }
        .padding(12)
        .background(HushTheme.card)
        .cornerRadius(10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
    }

    private var degradationLabel: String {
        switch recordDegradation {
        case 1: return "Degraded"
        case 2: return "Emergency"
        default: return "Normal"
        }
    }

    private var degradationColor: Color {
        switch recordDegradation {
        case 1: return .yellow
        case 2: return .red
        default: return Color(red: 0.0, green: 1.0, blue: 0.53)
        }
    }

    private var recorderButtonLabel: String {
        if !hasMicPermission { return "Grant Microphone Permission" }
        if isRecording { return "Stop Recording" }
        if recNeedsHush { return "Start Hush" }
        return "Start Recording"
    }

    private var recorderButtonIcon: String {
        if !hasMicPermission { return "mic.slash" }
        if isRecording { return "stop.circle.fill" }
        if recNeedsHush { return "wand.and.stars" }
        return "mic.fill"
    }

    private var recorderButtonColor: Color {
        if isRecording { return .red }
        return HushTheme.accent
    }

    // MARK: - Shared Helpers

    private func processedStatsCard(_ stats: HushStats) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("PROCESSING RESULTS")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.textDim)
            statRow("Input Samples", "\(stats.totalInputSamples)")
            statRow("Output Samples", "\(stats.totalOutputSamples)")
            statRow("Removed Samples", "\(stats.totalRemovedSamples)")
            statRow("Reduction", String(format: "%.2f%%", stats.reductionPercentage))
            statRow("Silent Segments", "\(stats.silentSegmentsDetected)")
            statRow("Time", String(format: "%.1fms", sfProcessingTime))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.black)
        .cornerRadius(10)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(HushTheme.cardBorder, lineWidth: 1))
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.system(size: 12, weight: .medium, design: .monospaced))
                .foregroundColor(HushTheme.textDim)
            Spacer()
            Text(value)
                .font(.system(size: 12, weight: .bold, design: .monospaced))
                .foregroundColor(HushTheme.text)
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        let mins = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", mins, secs)
    }

    // MARK: - Audio Session (Fix B)

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
        try? session.setActive(true)
    }

    /// Recorder's ma_context_uninit calls setActive:false — undo so playback keeps working.
    private func reactivateAudioSession() {
        configureAudioSession()
    }

    // MARK: - Engine

    private func ensureEngine() {
        guard hush == nil else { return }
        let config = HushConfig(
            thresholdDb: aggression.thresholdDb,
            aggressionLevel: aggression.aggression,
            sampleRate: 16000
        )
        hush = Hush(config: config)
    }

    private func recreateEngine(sampleRate: Int32) {
        hush?.close()
        let config = HushConfig(
            thresholdDb: aggression.thresholdDb,
            aggressionLevel: aggression.aggression,
            sampleRate: sampleRate
        )
        hush = Hush(config: config)
    }

    private func recreateEngineIfPossible() {
        let sr: Int32
        switch selectedTab {
        case .singleFile: sr = sfDecoded?.sampleRate ?? 16000
        case .batch: sr = 16000
        case .recorder: sr = recDecoded?.sampleRate ?? 16000
        }
        recreateEngine(sampleRate: sr)
    }

    // MARK: - Single File Actions

    private func handleFileImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }

        stopPlayback()
        pruneTempFiles()

        DispatchQueue.main.async { self.statusText = "Decoding \(url.lastPathComponent)..." }

        DispatchQueue.global(qos: .userInitiated).async {
            let accessing = url.startAccessingSecurityScopedResource()
            defer { if accessing { url.stopAccessingSecurityScopedResource() } }

            do {
                // Copy to cache so scope is not needed after import (Android temp_input pattern)
                let cached = FileManager.default.temporaryDirectory
                    .appendingPathComponent("temp_input_\(UUID().uuidString)_\(url.lastPathComponent)")
                try? FileManager.default.removeItem(at: cached)
                try FileManager.default.copyItem(at: url, to: cached)

                let decoded = try AudioDecoder.decode(fileAt: cached)
                DispatchQueue.main.async {
                    self.sfFileURL = cached
                    self.sfDecoded = decoded
                    self.sfProcessed = nil
                    self.sfStats = nil
                    self.sfTempWavURL = nil
                    self.playerMode = .original
                    self.statusText = "Decoded: \(decoded.samples.count) samples @ \(decoded.sampleRate)Hz"
                    self.recreateEngine(sampleRate: decoded.sampleRate)
                }
            } catch {
                DispatchQueue.main.async {
                    self.statusText = "Decode failed: \(error.localizedDescription)"
                }
            }
        }
    }

    private func processFile() {
        guard let audio = sfDecoded else { return }

        // C2: local engine, not the shared `hush` @State — closing on the bg
        // queue cannot race a UI engine swap or a tab switch.
        guard let engine = Hush(config: HushConfig(
            thresholdDb: aggression.thresholdDb,
            aggressionLevel: aggression.aggression,
            sampleRate: audio.sampleRate
        )) else {
            statusText = "Failed to create Hush engine."
            return
        }

        sfIsProcessing = true
        statusText = "Processing \(audio.samples.count) samples..."

        DispatchQueue.global(qos: .userInitiated).async {
            let start = CFAbsoluteTimeGetCurrent()
            let output = engine.process(input: audio.samples)
            let flushed = engine.flush()
            let allOutput = output + flushed
            let stats = engine.getStats()
            engine.close()
            let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000

            DispatchQueue.main.async {
                self.sfProcessed = allOutput
                self.sfStats = stats
                self.sfProcessingTime = elapsed
                self.sfIsProcessing = false
                self.playerMode = .processed
                self.statusText = "Processed \(audio.samples.count) → \(allOutput.count) samples\nReduction: \(String(format: "%.2f%%", stats.reductionPercentage))\nTime: \(String(format: "%.1fms", elapsed))"
                self.writeTempWav(samples: allOutput, sampleRate: audio.sampleRate, for: .singleFile)
            }
        }
    }

    /// `for:` pins the destination tab so a tab switch mid-process cannot misroute the WAV (C3).
    private func writeTempWav(samples: [Int16], sampleRate: Int32, for tab: HushTab) {
        pruneTempFiles()
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("hush_processed_\(UUID().uuidString).wav")
        do {
            try AudioWavWriter.write(samples: samples, sampleRate: sampleRate, to: tmp)
            switch tab {
            case .singleFile: sfTempWavURL = tmp
            case .recorder: recTempWavURL = tmp
            case .batch: break
            }
        } catch {
            statusText += "\nWAV write failed: \(error.localizedDescription)"
        }
    }

    // MARK: - Batch Directory Actions (Fix A: hold parent scope)

    private func handleInputDirectory(_ url: URL) {
        stopPlayback()

        // Release previous input dir grant before taking a new one — even for the
        // same URL, to keep start/stop balanced (C5).
        if inputDirAccessHeld, let old = inputDirectoryURL {
            old.stopAccessingSecurityScopedResource()
            inputDirAccessHeld = false
        }

        // Hold scope for the lifetime of this selection (batch + playback) — do NOT stop here
        let accessing = url.startAccessingSecurityScopedResource()
        inputDirAccessHeld = accessing
        inputDirectoryURL = url

        inputPlaylistFiles = scanDirectory(url)
        outputPlaylistFiles = []
        currentTrackIndex = 0
        batchStats = nil
        batchProcessedCount = 0
        batchTotalCount = inputPlaylistFiles.count
        if playerMode == .processed { playerMode = .original }

        if accessing {
            statusText = "Input: \(url.lastPathComponent) — \(inputPlaylistFiles.count) audio files"
        } else {
            statusText = "Warning: no sandbox access to \(url.lastPathComponent). \(inputPlaylistFiles.count) files listed; reads may fail."
        }
    }

    private func handleOutputDirectory(_ url: URL) {
        stopPlayback()
        outputDirectoryURL = url
        scanOutputPlaylist()
        currentTrackIndex = 0
        statusText = "Output: \(url.lastPathComponent)"
    }

    /// Scan output dir for processed files (P1: Original/Processed playlist switch).
    private func scanOutputPlaylist() {
        guard let out = outputDirectoryURL else {
            outputPlaylistFiles = []
            return
        }
        let accessing = out.startAccessingSecurityScopedResource()
        outputPlaylistFiles = scanDirectory(out)
        if accessing { out.stopAccessingSecurityScopedResource() }
    }

    private func scanDirectory(_ url: URL) -> [URL] {
        // Parent dir scope is already held by handleInputDirectory — child reads share that grant
        guard let items = try? FileManager.default.contentsOfDirectory(
            at: url,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return [] }

        return items.filter { item in
            let ext = item.pathExtension.lowercased()
            return ext == "wav" || ext == "mp3"
        }.sorted { $0.lastPathComponent.lowercased() < $1.lastPathComponent.lowercased() }
    }

    private func startBatchProcess() {
        guard let inputDir = inputDirectoryURL, let outputDir = outputDirectoryURL else { return }

        // C8: refuse same-dir batches — writes would truncate source WAVs in place
        if inputDir.standardizedFileURL == outputDir.standardizedFileURL {
            statusText = "Input and output directories must differ — batch would overwrite sources."
            return
        }

        let files = inputPlaylistFiles
        guard !files.isEmpty else { return }

        // Ensure input scope held before background work (held from pick; retry once if lost)
        if !inputDirAccessHeld {
            inputDirAccessHeld = inputDir.startAccessingSecurityScopedResource()
        }
        let inputHeld = inputDirAccessHeld
        guard inputHeld else {
            statusText = "Batch aborted: no access to input directory. Re-select it."
            return
        }

        stopPlayback()
        isBatchProcessing = true
        batchTotalCount = files.count
        batchProcessedCount = 0
        batchStats = nil
        statusText = "Starting batch: \(files.count) files..."

        DispatchQueue.global(qos: .userInitiated).async {
            // Hold output scope for the whole batch; input scope stays held from pick (never stopped mid-batch)
            let outputAccessing = outputDir.startAccessingSecurityScopedResource()
            defer { if outputAccessing { outputDir.stopAccessingSecurityScopedResource() } }

            var totalInput: Int64 = 0
            var totalOutput: Int64 = 0
            var totalRemoved: Int64 = 0
            var totalSilent: Int32 = 0
            var processed = 0
            var failures: [String] = []

            for (index, fileURL) in files.enumerated() {
                DispatchQueue.main.async {
                    self.batchProcessedCount = index
                    self.statusText = "Processing (\(index + 1)/\(files.count)):\n\(fileURL.lastPathComponent)"
                }

                do {
                    let decoded = try AudioDecoder.decode(fileAt: fileURL)
                    guard let engine = Hush(config: HushConfig(
                        thresholdDb: aggression.thresholdDb,
                        aggressionLevel: aggression.aggression,
                        sampleRate: decoded.sampleRate
                    )) else {
                        throw NSError(domain: "hush", code: -1,
                                      userInfo: [NSLocalizedDescriptionKey: "engine create failed"])
                    }
                    let output = engine.process(input: decoded.samples)
                    let flushed = engine.flush()
                    let allOutput = output + flushed
                    let stats = engine.getStats()
                    engine.close()

                    let baseName = fileURL.deletingPathExtension().lastPathComponent
                    // C8: de-dupe names within one run (a.wav + a.mp3 would collide on a.wav)
                    var outputURL = outputDir.appendingPathComponent("\(baseName).wav")
                    if outputURL.path != fileURL.path,
                       FileManager.default.fileExists(atPath: outputURL.path),
                       files.filter({ $0.deletingPathExtension().lastPathComponent == baseName }).count > 1 {
                        let ext = fileURL.pathExtension.lowercased()
                        outputURL = outputDir.appendingPathComponent("\(baseName)_\(ext).wav")
                    }
                    try AudioWavWriter.write(samples: allOutput, sampleRate: decoded.sampleRate, to: outputURL)

                    totalInput += stats.totalInputSamples
                    totalOutput += stats.totalOutputSamples
                    totalRemoved += stats.totalRemovedSamples
                    totalSilent += stats.silentSegmentsDetected
                    processed += 1
                } catch {
                    failures.append("\(fileURL.lastPathComponent) — \(error.localizedDescription)")
                    DispatchQueue.main.async {
                        self.statusText += "\nFailed: \(fileURL.lastPathComponent) — \(error.localizedDescription)"
                    }
                }
            }

            let stats = BatchStats(
                totalInput: totalInput,
                totalOutput: totalOutput,
                totalRemoved: totalRemoved,
                silentSegments: totalSilent,
                processed: processed,
                total: files.count,
                failures: failures
            )

            DispatchQueue.main.async {
                self.batchProcessedCount = processed
                self.batchStats = stats
                self.isBatchProcessing = false

                // P1: make processed outputs available to the playlist switch
                self.scanOutputPlaylist()
                if self.playerMode == .processed { self.currentTrackIndex = 0 }

                // Append failures to the summary — never overwrite them
                var summary = "Batch complete: \(processed)/\(files.count) files\n"
                summary += "Total input: \(totalInput) samples\n"
                summary += "Total output: \(totalOutput) samples\n"
                summary += "Removed: \(totalRemoved) samples\n"
                summary += "Reduction: \(String(format: "%.2f%%", stats.reduction))\n"
                summary += "Silent segments: \(totalSilent)"
                if !failures.isEmpty {
                    summary += "\nFailures (\(failures.count)):"
                    for f in failures {
                        summary += "\n  Failed: \(f)"
                    }
                }
                self.statusText = summary
            }
        }
    }

    // MARK: - Playlist / Playback

    private func copyToTemp(_ source: URL, prefix: String) -> URL? {
        // Hold scope during copy only; parent input-dir grant already covers child reads when held
        let accessing = source.startAccessingSecurityScopedResource()
        defer { if accessing { source.stopAccessingSecurityScopedResource() } }

        pruneTempFiles()
        let dest = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(prefix)_\(UUID().uuidString)_\(source.lastPathComponent)")
        do {
            try? FileManager.default.removeItem(at: dest)
            try FileManager.default.copyItem(at: source, to: dest)
            return dest
        } catch {
            return nil
        }
    }

    private func playTrack(_ index: Int) {
        guard index >= 0, index < playlistFiles.count else { return }

        stopPlayback()
        currentTrackIndex = index

        let url = playlistFiles[index]

        // Cache-copy so duration decode + play are immune to scope revoke (Android temp_play pattern)
        guard let cached = copyToTemp(url, prefix: "temp_play") else {
            statusText = "Cannot access \(url.lastPathComponent) for playback. Re-select input directory."
            return
        }

        beginPlayback(url: cached)
    }

    private func beginPlayback(url: URL) {
        if player == nil { player = AudioPlayer() }
        guard let p = player else { return }

        isPlaying = p.play(filepath: url.path)
        if !isPlaying {
            statusText = "Playback failed for \(url.lastPathComponent)"
            return
        }
        playbackFileURL = url
        isPaused = false

        playbackPosition = 0

        // C13: header-only duration (O(1)) — full decode just for seconds was wasteful
        do {
            let f = try AVAudioFile(forReading: url)
            playbackDuration = Double(f.length) / f.processingFormat.sampleRate
        } catch {
            playbackDuration = 0
        }

        startPlaybackTimer()
    }

    private func startPlaybackTimer() {
        playbackTimer?.invalidate()
        let startTime = Date()
        let startPosition = playbackPosition
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { timer in
            let elapsed = Date().timeIntervalSince(startTime)
            let newPos = startPosition + elapsed

            if let p = player, p.isFinished() {
                DispatchQueue.main.async {
                    self.isPlaying = false
                    self.playbackPosition = 0
                    self.playbackTimer?.invalidate()
                    self.playbackTimer = nil
                    if self.selectedTab == .batch && self.currentTrackIndex + 1 < self.playlistFiles.count {
                        self.playTrack(self.currentTrackIndex + 1)
                    }
                }
                return
            }

            if self.playbackDuration > 0 && newPos >= self.playbackDuration {
                DispatchQueue.main.async {
                    self.isPlaying = false
                    self.playbackPosition = 0
                    self.playbackTimer?.invalidate()
                    self.playbackTimer = nil
                    if self.selectedTab == .batch && self.currentTrackIndex + 1 < self.playlistFiles.count {
                        self.playTrack(self.currentTrackIndex + 1)
                    }
                }
                return
            }

            DispatchQueue.main.async {
                self.playbackPosition = newPos
            }
        }
    }

    private func stopPlaybackTimer() {
        playbackTimer?.invalidate()
        playbackTimer = nil
        playbackPosition = 0
    }

    private func togglePlayback() {
        if isPaused {
            // Resume from current position (C1): togglePause un-pauses; restart timer only.
            player?.togglePause()
            isPaused = false
            isPlaying = true
            startPlaybackTimer()
            return
        }

        if isPlaying {
            // Pause: stop the timer so position freezes and auto-advance cannot fire.
            player?.togglePause()
            isPaused = true
            isPlaying = false
            playbackTimer?.invalidate()
            playbackTimer = nil
            return
        }

        switch selectedTab {
        case .batch:
            playTrack(currentTrackIndex)
        case .singleFile:
            let url = playerMode == .original ? sfFileURL : sfTempWavURL
            guard let url else { return }
            beginPlayback(url: url)
        case .recorder:
            let url = playerMode == .original ? recFileURL : recTempWavURL
            guard let url else { return }
            beginPlayback(url: url)
        }
    }

    private func stopPlayback() {
        player?.stop()
        isPlaying = false
        isPaused = false
        playbackFileURL = nil // prune may now reclaim its temp copy
        stopPlaybackTimer()
    }

    // MARK: - Recorder Actions

    private func checkMicPermission() {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted: hasMicPermission = true
        case .denied: hasMicPermission = false
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async { self.hasMicPermission = granted }
            }
        @unknown default: break
        }
    }

    private func recorderButtonTapped() {
        if !hasMicPermission {
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async {
                    self.hasMicPermission = granted
                    self.statusText = granted
                        ? "Microphone permission granted. Tap 'Start Recording'."
                        : "Microphone permission denied."
                }
            }
            return
        }

        if isRecording {
            stopRecording()
        } else if recNeedsHush {
            startHushFromRecording()
        } else {
            startRecording()
        }
    }

    private func startRecording() {
        let tmpDir = FileManager.default.temporaryDirectory
        let outputFile = tmpDir.appendingPathComponent("recorded_audio.wav").path

        // Remove old recording
        try? FileManager.default.removeItem(atPath: outputFile)

        guard let rec = AudioRecorder(
            outputFile: outputFile,
            thresholdDb: aggression.thresholdDb,
            aggressionLevel: aggression.aggression,
            sampleRate: 16000,
            useSilenceRemoval: useSilenceRemoval
        ), rec.start() else {
            statusText = "Failed to start recording."
            return
        }

        // C10: miniaudio just rebuilt the shared AVAudioSession on context init —
        // re-assert the app category before anything else uses it.
        configureAudioSession()

        recorder = rec
        isRecording = true
        // New take invalidates any pending raw-recording hush request (C7: also
        // drop the previous take's file/decode so Play cannot reach a dead file)
        recNeedsHush = false
        recProcessed = nil
        recTempWavURL = nil
        recFileURL = nil
        recDecoded = nil
        recordDbLevel = -100
        recordPressure = 0
        recordDegradation = 0
        recordStats = nil
        playerMode = .original
        statusText = "Recording..."

        // Poll every 80ms — C12: native getters are FFI calls, run them off main;
        // hop back only to publish the values.
        recorderPollTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { _ in
            // ContentView is a struct — capture the current recorder ref, not self
            guard let rec = self.recorder else { return }
            DispatchQueue.global(qos: .userInteractive).async {
                let db = rec.getCurrentDb()
                let pressure = rec.getPressure()
                let degradation = rec.getDegradationState()
                let stats = rec.getStats()
                DispatchQueue.main.async {
                    guard self.recorder != nil else { return } // stopped mid-poll
                    self.recordDbLevel = db
                    self.recordPressure = pressure
                    self.recordDegradation = degradation
                    self.recordStats = stats
                }
            }
        }
    }

    private func stopRecording() {
        recorderPollTimer?.invalidate()
        recorderPollTimer = nil

        recorder?.stop()
        let stats = recorder?.getStats()
        recorder?.close()
        recorder = nil
        isRecording = false

        // C9: freeze live gauges — a stale dB/pressure reading after stop is misleading
        recordDbLevel = -100
        recordPressure = 0
        recordDegradation = 0

        // Fix B: recorder context uninit deactivated the shared AVAudioSession — reactivate for playback
        reactivateAudioSession()

        let recordedURL = FileManager.default.temporaryDirectory.appendingPathComponent("recorded_audio.wav")
        guard FileManager.default.fileExists(atPath: recordedURL.path) else {
            statusText = "Recording stopped but file not found."
            return
        }

        statusText = "Recording stopped. Decoding..."

        let removalOn = useSilenceRemoval
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let decoded = try AudioDecoder.decode(fileAt: recordedURL)
                DispatchQueue.main.async {
                    // Isolated recorder state only — never touches sf*
                    self.recFileURL = recordedURL
                    self.recDecoded = decoded
                    self.recordStats = stats
                    self.playerMode = .original

                    if removalOn {
                        // File already hushed live — expose it as both original and processed
                        self.recNeedsHush = false
                        self.recProcessed = decoded.samples
                        self.recTempWavURL = recordedURL
                        self.statusText = "Recording saved (hushed): \(decoded.samples.count) samples @ \(decoded.sampleRate)Hz"
                    } else {
                        // Raw take — require explicit Start Hush step
                        self.recNeedsHush = true
                        self.recProcessed = nil
                        self.recTempWavURL = nil
                        self.statusText = "Raw recording saved: \(decoded.samples.count) samples @ \(decoded.sampleRate)Hz\nTap Start Hush to process."
                    }

                    self.recreateEngine(sampleRate: decoded.sampleRate)
                }
            } catch {
                DispatchQueue.main.async {
                    self.statusText = "Recording stopped. Decode failed: \(error.localizedDescription)"
                }
            }
        }
    }

    /// Process a raw recording when silence-removal toggle was OFF during capture.
    private func startHushFromRecording() {
        guard let audio = recDecoded else { return }

        // C2: local engine closed on the bg queue — shared `hush` stays untouched
        guard let engine = Hush(config: HushConfig(
            thresholdDb: aggression.thresholdDb,
            aggressionLevel: aggression.aggression,
            sampleRate: audio.sampleRate
        )) else {
            statusText = "Failed to create Hush engine."
            return
        }

        statusText = "Processing recording (\(audio.samples.count) samples)..."

        DispatchQueue.global(qos: .userInitiated).async {
            let start = CFAbsoluteTimeGetCurrent()
            let output = engine.process(input: audio.samples)
            let flushed = engine.flush()
            let allOutput = output + flushed
            let stats = engine.getStats()
            engine.close()
            let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000

            DispatchQueue.main.async {
                self.recProcessed = allOutput
                self.recNeedsHush = false
                self.playerMode = .processed

                let tmp = FileManager.default.temporaryDirectory
                    .appendingPathComponent("hush_rec_processed_\(UUID().uuidString).wav")
                do {
                    try AudioWavWriter.write(samples: allOutput, sampleRate: audio.sampleRate, to: tmp)
                    self.recTempWavURL = tmp
                } catch {
                    self.statusText = "WAV write failed: \(error.localizedDescription)"
                }

                self.statusText = "Processed \(audio.samples.count) → \(allOutput.count) samples\nReduction: \(String(format: "%.2f%%", stats.reductionPercentage))\nTime: \(String(format: "%.1fms", elapsed))"
            }
        }
    }

    // MARK: - Temp Hygiene (C14)

    /// Sweep our temp artifacts. Skips anything currently referenced so a
    /// playing/decoded file can never be deleted under itself.
    private func pruneTempFiles() {
        let active: [URL?] = [
            sfFileURL, sfTempWavURL,
            recFileURL, recTempWavURL,
            playbackFileURL,
            inputDirectoryURL, outputDirectoryURL,
            outputPlaylistFiles.first, inputPlaylistFiles.first
        ]
        let activePaths = Set(active.compactMap { $0?.standardizedFileURL.path })

        let tmp = FileManager.default.temporaryDirectory
        let prefixes = ["temp_input_", "temp_play_", "hush_processed_", "hush_rec_processed_", "export_"]

        guard let items = try? FileManager.default.contentsOfDirectory(
            at: tmp, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]
        ) else { return }

        for item in items {
            let name = item.lastPathComponent
            guard prefixes.contains(where: { name.hasPrefix($0) }) else { continue }
            guard !activePaths.contains(item.standardizedFileURL.path) else { continue }
            try? FileManager.default.removeItem(at: item)
        }
    }

    // MARK: - Cleanup

    private func cleanup() {
        stopPlayback()
        recorderPollTimer?.invalidate()
        recorder?.close()
        recorder = nil
        // C10: recorder close deactivates the session — restore it for any
        // engine that outlives this view (or a fast re-appear).
        reactivateAudioSession()

        if inputDirAccessHeld, let inputDir = inputDirectoryURL {
            inputDir.stopAccessingSecurityScopedResource()
            inputDirAccessHeld = false
        }
    }
}

// MARK: - Preview

#Preview {
    ContentView()
}

// MARK: - WavDocument

struct WavDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.wav] }

    let samples: [Int16]
    let sampleRate: Int32

    init(samples: [Int16], sampleRate: Int32) {
        self.samples = samples
        self.sampleRate = sampleRate
    }

    init(configuration: ReadConfiguration) throws {
        throw CocoaError(.featureUnsupported)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("export_\(UUID().uuidString).wav")
        try AudioWavWriter.write(samples: samples, sampleRate: sampleRate, to: tmp)
        let data = try Data(contentsOf: tmp)
        return FileWrapper(regularFileWithContents: data)
    }
}
