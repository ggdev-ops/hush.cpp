import SwiftUI
import UniformTypeIdentifiers

// MARK: - Directory Picker
// Wraps UIDocumentPickerViewController for picking directories.
// .fileImporter with .folder type on iOS 14+.

struct DirectoryPicker: UIViewControllerRepresentable {
    @Binding var isPresented: Bool
    let onSelect: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            documentTypes: ["public.folder"],
            in: .open
        )
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let parent: DirectoryPicker

        init(_ parent: DirectoryPicker) {
            self.parent = parent
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            parent.onSelect(url)
            parent.isPresented = false
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            parent.isPresented = false
        }
    }
}
