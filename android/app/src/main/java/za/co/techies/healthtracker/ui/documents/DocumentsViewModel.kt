// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. ViewModel Overview. [Online]. Available at:
//    https://developer.android.com/topic/libraries/architecture/viewmodel [Accessed 25 September 2026].
//   Google. 2024. Architecture: UI layer. [Online]. Available at:
//    https://developer.android.com/topic/architecture/ui-layer [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 3.9 (Additional Feature 2 —
//    Document Management / Capturing) and section 4.11 (Screen 11 — Documents).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.documents

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.files.PickedFile
import za.co.techies.healthtracker.core.files.UploadRules
import za.co.techies.healthtracker.core.session.Session
import za.co.techies.healthtracker.core.text.UiText
import za.co.techies.healthtracker.core.text.asUiText
import za.co.techies.healthtracker.core.text.uiText
import za.co.techies.healthtracker.data.remote.dto.DocumentDto
import za.co.techies.healthtracker.data.repository.DocumentRepository
import za.co.techies.healthtracker.di.AppContainer

/**
 * What the Documents screen is showing.
 *
 * [pending] holds a file the caregiver has chosen but not yet described. Upload needs a title and
 * a category, and asking for those before the file exists would mean typing a title for something
 * they might not find — so the picker comes first and the form opens over the result.
 */
data class DocumentsUiState(
    val isLoading: Boolean = true,
    val error: UiText? = null,
    val documents: List<DocumentDto> = emptyList(),
    val dependantName: String = "",
    val hasDependant: Boolean = true,

    val pending: PickedFile? = null,
    val title: String = "",
    val documentType: String = UploadRules.DocumentTypes.first(),
    val isUploading: Boolean = false,
    val formError: UiText? = null,

    /** The document currently being deleted, so only its own row shows a spinner. */
    val deletingId: String? = null,
    val message: UiText? = null
)

/**
 * The Documents screen (Additional Feature 2).
 *
 * Every file lives in the API's storage and is reached through a signed, time-limited URL that the
 * server mints on each read — the client never sees a storage path, and a link that leaks stops
 * working within fifteen minutes.
 */
class DocumentsViewModel(
    private val repository: DocumentRepository,
    private val session: Session
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    val documentTypes: List<String> = UploadRules.DocumentTypes

    fun load() {
        val dependant = session.current.selectedDependant

        if (dependant == null) {
            _uiState.update { it.copy(isLoading = false, hasDependant = false) }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = it.documents.isEmpty(),
                error = null,
                hasDependant = true,
                dependantName = dependant.firstName
            )
        }

        viewModelScope.launch {
            repository.listForDependant(dependant.dependantId)
                .onSuccess { documents ->
                    _uiState.update {
                        it.copy(isLoading = false, documents = documents, error = null)
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not load documents", throwable)
                    _uiState.update { it.copy(isLoading = false, error = throwable.toUiText()) }
                }
        }
    }

    /**
     * Accepts a picked or captured file, or explains why it cannot be used.
     *
     * Both checks exist on the server too. Repeating them here is not distrust of the API — it is
     * that failing now costs one dialog, and failing at the server costs the caregiver ten
     * megabytes of mobile data first.
     */
    fun onFilePicked(file: PickedFile?) {
        if (file == null) {
            _uiState.update { it.copy(formError = uiText(R.string.doc_err_unreadable)) }
            return
        }

        if (!UploadRules.isAcceptableType(file.contentType)) {
            _uiState.update { it.copy(pending = null, formError = uiText(R.string.doc_err_type)) }
            return
        }

        if (!UploadRules.isAcceptableSize(file.sizeBytes)) {
            _uiState.update {
                it.copy(
                    pending = null,
                    formError = uiText(
                        R.string.doc_err_too_large,
                        UploadRules.MaximumFileSizeBytes / (1024 * 1024)
                    )
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                pending = file,
                // Seeded from the file name so the common case is one tap. Prescriptions arrive
                // called things like "IMG_20260925_1442.jpg", which is worth replacing, but a
                // document saved as "Dr Nkosi script.pdf" already has the better title.
                title = file.displayName.substringBeforeLast('.').take(TitleLimit),
                formError = null
            )
        }
    }

    fun onTitle(value: String) = _uiState.update { it.copy(title = value.take(TitleLimit)) }

    fun onDocumentType(value: String) = _uiState.update { it.copy(documentType = value) }

    fun cancelUpload() = _uiState.update {
        it.copy(pending = null, title = "", formError = null)
    }

    fun upload() {
        val state = _uiState.value
        val file = state.pending ?: return
        val dependantId = session.current.selectedDependant?.dependantId ?: return

        if (state.title.isBlank()) {
            _uiState.update { it.copy(formError = uiText(R.string.doc_err_title_required)) }
            return
        }

        _uiState.update { it.copy(isUploading = true, formError = null) }

        viewModelScope.launch {
            repository.upload(dependantId, file, state.title, state.documentType)
                .onSuccess { document ->
                    Log.i(TAG, "Uploaded document ${document.documentId}")

                    _uiState.update {
                        it.copy(
                            isUploading = false,
                            pending = null,
                            title = "",
                            // Prepended rather than re-listing: the API returns newest first and
                            // this one is newest, so a round trip would say exactly the same thing.
                            documents = listOf(document) + it.documents,
                            message = uiText(R.string.doc_uploaded)
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Upload failed", throwable)
                    _uiState.update {
                        it.copy(isUploading = false, formError = throwable.toUiText())
                    }
                }
        }
    }

    fun delete(documentId: String) {
        if (_uiState.value.deletingId != null) return

        _uiState.update { it.copy(deletingId = documentId) }

        viewModelScope.launch {
            repository.delete(documentId)
                .onSuccess {
                    Log.i(TAG, "Deleted document $documentId")

                    _uiState.update {
                        it.copy(
                            deletingId = null,
                            documents = it.documents.filterNot { doc -> doc.documentId == documentId },
                            message = uiText(R.string.doc_deleted)
                        )
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not delete document $documentId", throwable)
                    _uiState.update { it.copy(deletingId = null, error = throwable.toUiText()) }
                }
        }
    }

    /**
     * Asks the API for a freshly signed link.
     *
     * The one in the list was signed when the list was fetched and lasts fifteen minutes. A
     * caregiver who opens the screen, reads something else and comes back would otherwise tap a
     * document and get a refusal from a link that quietly expired while they were elsewhere.
     */
    fun openDocument(documentId: String, onReady: (String) -> Unit) {
        viewModelScope.launch {
            repository.get(documentId)
                .onSuccess { document ->
                    if (document.downloadUrl.isBlank()) {
                        _uiState.update { it.copy(error = uiText(R.string.doc_err_no_link)) }
                    } else {
                        onReady(document.downloadUrl)
                    }
                }
                .onFailure { throwable ->
                    Log.w(TAG, "Could not refresh the download link", throwable)
                    _uiState.update { it.copy(error = throwable.toUiText()) }
                }
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    private fun Throwable.toUiText(): UiText =
        message?.asUiText() ?: uiText(R.string.doc_err_generic)

    companion object {
        private const val TAG = "DocumentsViewModel"

        /** Matches the API's StringLength(150) on the title. */
        const val TitleLimit = 150

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = DocumentsViewModel(
                    repository = container.documentRepository,
                    session = container.session
                ) as T
            }
    }
}
