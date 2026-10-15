// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Lists and Grids in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/lists [Accessed 25 September 2026].
//   Google. 2024. Activity Result APIs in Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/libraries#activity_result
//    [Accessed 25 September 2026].
//   Google. 2024. Open files using storage access framework. [Online]. Available at:
//    https://developer.android.com/guide/topics/providers/document-provider
//    [Accessed 25 September 2026].
//   Google. 2024. Take photos. [Online]. Available at:
//    https://developer.android.com/training/camera/photobasics [Accessed 25 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.11 (Screen 11 —
//    Documents) and section 3.9 (Additional Feature 2).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.documents

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.core.files.DocumentCapture
import za.co.techies.healthtracker.core.files.UploadRules
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.data.remote.dto.DocumentDto
import za.co.techies.healthtracker.ui.components.EmptyState
import za.co.techies.healthtracker.ui.components.Formatting
import za.co.techies.healthtracker.ui.components.LoadingState
import za.co.techies.healthtracker.ui.components.documentTypeLabel

/**
 * Documents — screen 11 of the design document (section 4.11), satisfying Additional Feature 2.
 *
 * Two ways in, because section 3.9 asks for both: capture a prescription with the camera, or pick
 * a file the caregiver already has. Everything else — where the bytes live, who may read them,
 * how long a link lasts — is the API's business, and this screen never sees a storage path.
 */
@Composable
fun DocumentsScreen(
    state: DocumentsUiState,
    documentTypes: List<String>,
    onFilePicked: (Uri?) -> Unit,
    onTitle: (String) -> Unit,
    onDocumentType: (String) -> Unit,
    onUpload: () -> Unit,
    onCancelUpload: () -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String, (String) -> Unit) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // OpenDocument rather than GetContent: it filters by the exact types the API accepts, so an
    // unsupported file is not offered in the first place instead of being refused after picking.
    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onFilePicked
    )

    // The camera writes into a URI we supply, so it has to be created before launching and kept
    // for the callback — the contract only reports whether the picture was taken.
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    val takePhoto = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved -> onFilePicked(if (saved) captureUri else null) }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading && state.documents.isEmpty() -> LoadingState()

            !state.hasDependant -> EmptyState(
                title = stringResource(R.string.doc_no_dependant_title),
                message = stringResource(R.string.doc_no_dependant_body)
            )

            state.error != null && state.documents.isEmpty() -> LoadFailure(
                message = state.error.resolve(),
                onRetry = onRetry
            )

            else -> DocumentList(
                state = state,
                context = context,
                onOpen = onOpen,
                onDelete = onDelete,
                modifier = Modifier.weight(1f)
            )
        }

        if (state.hasDependant) {
            UploadActions(
                enabled = !state.isUploading,
                onPick = { pickFile.launch(UploadRules.PickerMimeTypes) },
                onCapture = {
                    val uri = DocumentCapture.newImageUri(context)
                    captureUri = uri

                    try {
                        takePhoto.launch(uri)
                    } catch (missing: ActivityNotFoundException) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.doc_err_no_camera),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }

    if (state.pending != null) {
        UploadDialog(
            state = state,
            documentTypes = documentTypes,
            onTitle = onTitle,
            onDocumentType = onDocumentType,
            onUpload = onUpload,
            onCancel = onCancelUpload
        )
    }

    // A rejected file never becomes a pending upload, so its explanation has nowhere else to go.
    if (state.pending == null && state.formError != null) {
        AlertDialog(
            onDismissRequest = onCancelUpload,
            title = { Text(stringResource(R.string.doc_cannot_upload)) },
            text = { Text(state.formError.resolve()) },
            confirmButton = {
                TextButton(onClick = onCancelUpload) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }
}

@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.state_error_title),
            style = MaterialTheme.typography.titleMedium
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Button(onClick = onRetry) { Text(stringResource(R.string.state_retry)) }
    }
}

@Composable
private fun DocumentList(
    state: DocumentsUiState,
    context: Context,
    onOpen: (String, (String) -> Unit) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.documents.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.doc_empty_title),
            message = stringResource(R.string.doc_empty_body),
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        items(state.documents, key = { it.documentId }) { document ->
            DocumentCard(
                document = document,
                isDeleting = state.deletingId == document.documentId,
                onOpen = {
                    // The link in this row was signed when the list was fetched and lasts fifteen
                    // minutes, so a fresh one is fetched at the moment of opening rather than
                    // trusting the one already in hand.
                    onOpen(document.documentId) { url -> context.view(url, document.contentType) }
                },
                onDelete = { onDelete(document.documentId) }
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DocumentCard(
    document: DocumentDto,
    isDeleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    var confirming by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDeleting, onClick = onOpen)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = documentTypeLabel(document.documentType),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Text(
                    text = stringResource(
                        R.string.doc_size_and_date,
                        Formatting.fileSize(document.fileSizeBytes),
                        Formatting.dateTime(document.uploadedAt)
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isDeleting) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = { confirming = true }) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.doc_delete_title)) },
            // Named rather than "this document": a caregiver managing several people's
            // prescriptions should see which one is about to go.
            text = { Text(stringResource(R.string.doc_delete_body, document.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onDelete()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun UploadActions(enabled: Boolean, onPick: () -> Unit, onCapture: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(onClick = onCapture, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.doc_capture))
        }

        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.doc_upload_file))
        }
    }
}

@Composable
private fun UploadDialog(
    state: DocumentsUiState,
    documentTypes: List<String>,
    onTitle: (String) -> Unit,
    onDocumentType: (String) -> Unit,
    onUpload: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!state.isUploading) onCancel() },
        title = { Text(stringResource(R.string.doc_add_title)) },
        text = {
            Column {
                state.pending?.let { file ->
                    Text(
                        text = stringResource(
                            R.string.doc_chosen_file,
                            file.displayName,
                            Formatting.fileSize(file.sizeBytes)
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = state.title,
                    onValueChange = onTitle,
                    label = { Text(stringResource(R.string.doc_title_label)) },
                    singleLine = true,
                    enabled = !state.isUploading,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.doc_type_label),
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(Modifier.height(6.dp))

                // Chips rather than free text: the API rejects anything outside its four
                // categories, so there is no reason to let it be typed.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    documentTypes.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { type ->
                                FilterChip(
                                    selected = type == state.documentType,
                                    enabled = !state.isUploading,
                                    onClick = { onDocumentType(type) },
                                    label = { Text(documentTypeLabel(type)) }
                                )
                            }
                        }
                    }
                }

                state.formError?.let { message ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = message.resolve(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpload, enabled = !state.isUploading) {
                if (state.isUploading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.doc_upload))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !state.isUploading) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Opens a document through its signed link.
 *
 * The URL carries its own authorisation, so it is handed to whatever app the phone uses for that
 * content type. `FLAG_GRANT_READ_URI_PERMISSION` costs nothing on an http URL and matters if this
 * ever opens a local one.
 */
private fun Context.view(url: String, contentType: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(url), contentType.ifBlank { "*/*" })
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        startActivity(intent)
    } catch (missing: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.doc_err_no_viewer), Toast.LENGTH_SHORT).show()
    }
}
