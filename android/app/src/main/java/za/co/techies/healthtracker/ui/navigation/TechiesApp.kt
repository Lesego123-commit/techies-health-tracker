// ----------------------------------------------------------------------------------------------
//  References
//   Google. 2024. Navigation with Compose. [Online]. Available at:
//    https://developer.android.com/develop/ui/compose/navigation [Accessed 16 September 2026].
//   Google. 2024. Material 3 Navigation Bar. [Online]. Available at:
//    https://m3.material.io/components/navigation-bar/overview [Accessed 16 September 2026].
//   Google. 2024. Material 3 Top App Bar. [Online]. Available at:
//    https://m3.material.io/components/top-app-bar/overview [Accessed 16 September 2026].
//   Techies. 2026. PROG7314 POE Part 1: Planning and Design, section 4.14 (Navigation Flow).
// ----------------------------------------------------------------------------------------------

package za.co.techies.healthtracker.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import za.co.techies.healthtracker.core.text.resolve
import za.co.techies.healthtracker.R
import za.co.techies.healthtracker.di.AppContainer
import za.co.techies.healthtracker.ui.appointments.AppointmentsScreen
import za.co.techies.healthtracker.ui.appointments.AppointmentsViewModel
import za.co.techies.healthtracker.ui.auth.SignInScreen
import za.co.techies.healthtracker.ui.auth.SignInViewModel
import za.co.techies.healthtracker.ui.components.resolve
import za.co.techies.healthtracker.ui.dependants.DependantEditScreen
import za.co.techies.healthtracker.ui.dependants.DependantEditViewModel
import za.co.techies.healthtracker.ui.dependants.DependantProfileScreen
import za.co.techies.healthtracker.ui.dependants.DependantProfileViewModel
import za.co.techies.healthtracker.core.files.describe
import za.co.techies.healthtracker.ui.dependants.DependantsScreen
import za.co.techies.healthtracker.ui.documents.DocumentsScreen
import za.co.techies.healthtracker.ui.documents.DocumentsViewModel
import za.co.techies.healthtracker.ui.dependants.DependantsViewModel
import za.co.techies.healthtracker.ui.home.HomeScreen
import za.co.techies.healthtracker.ui.home.HomeViewModel
import za.co.techies.healthtracker.ui.medication.MedicationFormScreen
import za.co.techies.healthtracker.ui.medication.MedicationFormViewModel
import za.co.techies.healthtracker.ui.pharmacy.PharmacyScreen
import za.co.techies.healthtracker.ui.pharmacy.PharmacyViewModel
import za.co.techies.healthtracker.ui.notifications.NotificationsScreen
import za.co.techies.healthtracker.ui.notifications.NotificationsViewModel
import za.co.techies.healthtracker.ui.refill.RefillScreen
import za.co.techies.healthtracker.ui.refill.RefillViewModel
import za.co.techies.healthtracker.ui.settings.SettingsScreen
import za.co.techies.healthtracker.ui.settings.SettingsViewModel
import za.co.techies.healthtracker.ui.trends.TrendsScreen
import za.co.techies.healthtracker.ui.trends.TrendsViewModel

/**
 * The application shell, following the navigation graph in Figure 2 of the design document.
 *
 * Three levels, as the document specifies. The five bottom-navigation tabs stay visible
 * throughout; first-level drill-downs (Dependant Profile, Notifications) and second-level ones
 * (Add/Edit Medication, Refill Forecast) replace the bar with a back arrow, because they concern
 * one record rather than a section of the app.
 *
 * Sign In sits outside all of it and is cleared from the back stack on entry, so the hardware
 * back button cannot return to it from Home.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TechiesApp(container: AppContainer) {
    val navController = rememberNavController()
    val sessionState by container.session.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    val isAuthScreen = route == Routes.SIGN_IN || route == null
    val isTopLevel = navTabs.any { it.route == route }
    val showChrome = !isAuthScreen

    Scaffold(
        topBar = {
            if (showChrome) {
                TopAppBar(
                    title = { Text(titleFor(route, sessionState.selectedDependant?.firstName)) },
                    navigationIcon = {
                        if (!isTopLevel) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back)
                                )
                            }
                        }
                    },
                    actions = {
                        // The design reaches Notifications from a bell in the app bar rather than
                        // the bottom nav (section 4.12), keeping the five tabs for the main areas.
                        if (isTopLevel) {
                            IconButton(onClick = { navController.navigate(Routes.NOTIFICATIONS) }) {
                                Icon(
                                    Icons.Filled.Notifications,
                                    contentDescription = stringResource(R.string.action_notifications)
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showChrome && isTopLevel) {
                NavigationBar {
                    navTabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { navController.switchTab(tab.route, route) },
                            icon = {
                                Icon(
                                    imageVector = tab.icon,

                                    // Null rather than repeating the label: it sits directly
                                    // beneath, so a screen reader would announce it twice.
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(tab.labelRes)) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            // A FAB only where the design shows one: adding a medication from a dependant's
            // profile, as in section 4.5.
            if (route?.startsWith("dependant_profile") == true) {
                FloatingActionButton(onClick = { navController.navigate(Routes.ADD_MEDICATION) }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.action_add_medication)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SIGN_IN,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.SIGN_IN) {
                val viewModel: SignInViewModel = viewModel(factory = SignInViewModel.factory(container))
                val state by viewModel.uiState.collectAsState()

                LaunchedEffect(state.isSignedIn) {
                    if (state.isSignedIn) {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.SIGN_IN) { inclusive = true }
                        }
                    }
                }

                SignInScreen(
                    state = state,
                    onSignInWithGoogle = viewModel::signIn,
                    onEmail = viewModel::onEmail,
                    onPassword = viewModel::onPassword,
                    onConfirmPassword = viewModel::onConfirmPassword,
                    onDisplayName = viewModel::onDisplayName,
                    onMode = viewModel::setMode,
                    onSubmit = viewModel::submitEmailForm,
                    onForgotPassword = viewModel::sendPasswordReset,
                    onLanguage = viewModel::selectLanguage
                )
            }

            composable(Routes.HOME) {
                val viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                val state by viewModel.uiState.collectAsState()

                LoadOnResume { viewModel.load() }

                state.message?.let { message ->
                    // Resolved here rather than in the ViewModel, so the snackbar is written in the
                    // language selected at the moment it is shown.
                    val text = message.resolve()

                    LaunchedEffect(message) {
                        snackbarHostState.showSnackbar(text)
                        viewModel.dismissMessage()
                    }
                }

                HomeScreen(
                    state = state,
                    onLogDose = viewModel::logDose,
                    onSelectDependant = viewModel::selectDependant,
                    onAddDependant = { navController.switchTab(Routes.MEDS, route) },
                    onOpenDependant = { navController.navigate(Routes.dependantProfile(it)) },
                    onSyncNow = viewModel::syncNow,
                    onRetry = viewModel::load
                )
            }

            composable(Routes.MEDS) {
                val viewModel: DependantsViewModel = viewModel(
                    factory = DependantsViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()

                LoadOnResume { viewModel.load() }

                DependantsScreen(
                    state = state,
                    relationships = viewModel.relationships,
                    onSelect = viewModel::select,
                    onOpenProfile = { navController.navigate(Routes.dependantProfile(it)) },
                    onRetry = viewModel::load,
                    onOpenForm = viewModel::openForm,
                    onCloseForm = viewModel::closeForm,
                    onFirstName = viewModel::onFirstName,
                    onLastName = viewModel::onLastName,
                    onDateOfBirth = viewModel::onDateOfBirth,
                    onRelationship = viewModel::onRelationship,
                    onAllergies = viewModel::onAllergies,
                    onSave = viewModel::save
                )
            }

            composable(
                route = Routes.DEPENDANT_PROFILE,
                arguments = listOf(navArgument("dependantId") { type = NavType.StringType })
            ) { entry ->
                val dependantId = entry.arguments?.getString("dependantId").orEmpty()

                val viewModel: DependantProfileViewModel = viewModel(
                    factory = DependantProfileViewModel.factory(container, dependantId)
                )
                val state by viewModel.uiState.collectAsState()

                // Opening a profile also makes that dependant the one Trends, Appointments and
                // Refill refer to, so the tabs stay consistent with what is being looked at.
                LaunchedEffect(dependantId) { container.session.selectDependant(dependantId) }

                LoadOnResume { viewModel.load() }

                DependantProfileScreen(
                    state = state,
                    onAddMedication = { navController.navigate(Routes.ADD_MEDICATION) },
                    onEditMedication = { navController.navigate(Routes.editMedication(it)) },
                    onEditDependant = { navController.navigate(Routes.editDependant(dependantId)) },
                    onOpenRefillForecast = { navController.navigate(Routes.REFILL_FORECAST) },
                    onOpenDocuments = { navController.navigate(Routes.DOCUMENTS) },
                    onRetry = viewModel::load
                )
            }

            composable(Routes.TRENDS) {
                val viewModel: TrendsViewModel = viewModel(factory = TrendsViewModel.factory(container))
                val state by viewModel.uiState.collectAsState()

                LoadOnResume { viewModel.load() }

                TrendsScreen(
                    state = state,
                    onRangeChange = viewModel::setRange,
                    onRetry = viewModel::load
                )
            }

            composable(Routes.APPOINTMENTS) {
                val viewModel: AppointmentsViewModel = viewModel(
                    factory = AppointmentsViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()

                LoadOnResume { viewModel.load() }

                AppointmentsScreen(
                    state = state,
                    reminderOptions = viewModel.reminderOptions,
                    onOpenForm = viewModel::openForm,
                    onCloseForm = viewModel::closeForm,
                    onTitle = viewModel::onTitle,
                    onDate = viewModel::onDate,
                    onTime = viewModel::onTime,
                    onLocation = viewModel::onLocation,
                    onReminder = viewModel::onReminder,
                    onSave = viewModel::save,
                    onCancelAppointment = viewModel::cancel,
                    onRetry = viewModel::load
                )
            }

            composable(Routes.REFILL_FORECAST) {
                val viewModel: RefillViewModel = viewModel(factory = RefillViewModel.factory(container))
                val state by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                RefillScreen(
                    state = state,
                    // Was a bare `geo:0,0?q=pharmacy` hand-off, which asked the maps app to guess
                    // where the user was. The finder knows: it searches a chosen radius around an
                    // actual fix and comes back sorted by distance.
                    onFindPharmacy = { navController.navigate(Routes.PHARMACY_FINDER) },
                    onRetry = viewModel::load
                )
            }

            composable(Routes.DOCUMENTS) {
                val viewModel: DocumentsViewModel = viewModel(
                    factory = DocumentsViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()
                val context = LocalContext.current

                LoadOnResume { viewModel.load() }

                state.message?.let { message ->
                    val text = message.resolve()

                    LaunchedEffect(message) {
                        snackbarHostState.showSnackbar(text)
                        viewModel.dismissMessage()
                    }
                }

                DocumentsScreen(
                    state = state,
                    documentTypes = viewModel.documentTypes,
                    // The picker hands back a URI; describing it is a ContentResolver job, which
                    // belongs here rather than in a ViewModel that would then need a Context.
                    onFilePicked = { uri ->
                        viewModel.onFilePicked(uri?.let { context.contentResolver.describe(it) })
                    },
                    onTitle = viewModel::onTitle,
                    onDocumentType = viewModel::onDocumentType,
                    onUpload = viewModel::upload,
                    onCancelUpload = viewModel::cancelUpload,
                    onDelete = viewModel::delete,
                    onOpen = viewModel::openDocument,
                    onRetry = viewModel::load
                )
            }

            composable(Routes.PHARMACY_FINDER) {
                val viewModel: PharmacyViewModel = viewModel(
                    factory = PharmacyViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()

                // No LoadOnResume here, unlike the other screens. Coming back from the system
                // permission dialog is a resume, and re-running the search on it would fire a
                // second location request on top of the answer the user just gave.
                PharmacyScreen(
                    state = state,
                    radiusChoices = viewModel.radiusChoices,
                    onSearch = viewModel::search,
                    onRadius = viewModel::onRadius,
                    onPermissionResult = viewModel::onPermissionResult
                )
            }

            composable(Routes.MORE) {
                val viewModel: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()

                LaunchedEffect(state.signedOut) {
                    if (state.signedOut) {
                        navController.navigate(Routes.SIGN_IN) {
                            // Clearing the whole graph means no screen holding the previous
                            // user's data survives a sign-out.
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }

                state.message?.let { message ->
                    // Resolved here rather than in the ViewModel, so the snackbar is written in the
                    // language selected at the moment it is shown.
                    val text = message.resolve()

                    LaunchedEffect(message) {
                        snackbarHostState.showSnackbar(text)
                        viewModel.dismissMessage()
                    }
                }

                SettingsScreen(
                    state = state,
                    languages = viewModel.languages,
                    onLanguage = viewModel::setLanguage,
                    onDarkTheme = viewModel::setDarkTheme,
                    onBiometric = viewModel::setBiometric,
                    onNotifications = viewModel::setNotifications,
                    onReminderLeadTime = viewModel::setReminderLeadTime,
                    onLowStockThreshold = viewModel::setLowStockThreshold,
                    onQuietHours = viewModel::setQuietHours,
                    onFindPharmacy = { navController.navigate(Routes.PHARMACY_FINDER) },
                    onSignOut = viewModel::signOut,
                    onRetry = viewModel::load
                )
            }

            composable(Routes.NOTIFICATIONS) {
                val viewModel: NotificationsViewModel = viewModel(
                    factory = NotificationsViewModel.factory(container)
                )
                val state by viewModel.uiState.collectAsState()

                NotificationsScreen(state = state, onRetry = viewModel::load)
            }

            composable(Routes.ADD_MEDICATION) {
                MedicationFormRoute(
                    container = container,
                    medicationId = null,
                    savedMessage = "Medication added",
                    snackbarHostState = snackbarHostState,
                    onDone = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.EDIT_MEDICATION,
                arguments = listOf(navArgument("medicationId") { type = NavType.StringType })
            ) { entry ->
                MedicationFormRoute(
                    container = container,
                    medicationId = entry.arguments?.getString("medicationId"),
                    savedMessage = "Medication updated",
                    snackbarHostState = snackbarHostState,
                    onDone = { navController.popBackStack() }
                )
            }

            composable(
                route = Routes.EDIT_DEPENDANT,
                arguments = listOf(navArgument("dependantId") { type = NavType.StringType })
            ) { entry ->
                val dependantId = entry.arguments?.getString("dependantId").orEmpty()

                val viewModel: DependantEditViewModel = viewModel(
                    factory = DependantEditViewModel.factory(container, dependantId)
                )
                val state by viewModel.uiState.collectAsState()

                LaunchedEffect(state.isSaved) {
                    if (state.isSaved) {
                        snackbarHostState.showSnackbar("Profile updated")
                        navController.popBackStack()
                    }
                }

                DependantEditScreen(
                    state = state,
                    relationships = viewModel.relationships,
                    onFirstName = viewModel::onFirstName,
                    onLastName = viewModel::onLastName,
                    onDateOfBirth = viewModel::onDateOfBirth,
                    onRelationship = viewModel::onRelationship,
                    onMedicalAidNumber = viewModel::onMedicalAidNumber,
                    onAllergies = viewModel::onAllergies,
                    onSave = viewModel::save
                )
            }
        }
    }
}

/**
 * Calls [load] every time this destination resumes — on arrival, on returning from a form stacked
 * on top of it, and on coming back from the background.
 *
 * This is the only place these screens load, because a ViewModel scoped to a navigation entry
 * outlives the entry being covered and uncovered. Loading in the ViewModel's `init` instead meant
 * the profile behind the Add Medication form kept saying "no active medications" about a
 * medication the server had just accepted — the worst kind of bug, because nothing was lost and
 * it looked exactly like a failed save.
 *
 * An earlier version of this helper skipped the first resume, on the assumption that `init` had
 * already covered it. That was wrong in precisely the case it existed for: Navigation Compose
 * disposes a covered destination's composable, so the remembered "first" flag was reset by the
 * time the screen came back and it skipped the load it was added to perform.
 */
@Composable
private fun LoadOnResume(load: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLoad by rememberUpdatedState(load)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentLoad()
        }

        // addObserver replays up to the current state, so a destination that is already resumed
        // when this registers still gets its load.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Add and Edit share one form, so they share one route body. */
@Composable
private fun MedicationFormRoute(
    container: AppContainer,
    medicationId: String?,
    savedMessage: String,
    snackbarHostState: SnackbarHostState,
    onDone: () -> Unit
) {
    val viewModel: MedicationFormViewModel = viewModel(
        factory = MedicationFormViewModel.factory(container, medicationId)
    )
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            snackbarHostState.showSnackbar(savedMessage)
            onDone()
        }
    }

    MedicationFormScreen(
        state = state,
        dosageUnits = viewModel.dosageUnits,
        forms = viewModel.forms,
        onName = viewModel::onName,
        onDosageAmount = viewModel::onDosageAmount,
        onDosageUnit = viewModel::onDosageUnit,
        onForm = viewModel::onForm,
        onTotalQuantity = viewModel::onTotalQuantity,
        onRemainingQuantity = viewModel::onRemainingQuantity,
        onInstructions = viewModel::onInstructions,
        onFrequency = viewModel::onFrequency,
        onTime = viewModel::onTime,
        onSave = viewModel::save
    )
}

/** Switches tab without growing the back stack every time one is tapped. */
private fun NavHostController.switchTab(target: String, current: String?) {
    if (target == current) return

    navigate(target) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The app bar title for a destination.
 *
 * A composable so the titles come from resources and change with the language. The dependant's
 * own name is deliberately not translated — it is data, not chrome.
 */
@Composable
private fun titleFor(route: String?, dependantName: String?): String = when {
    route == Routes.HOME -> stringResource(R.string.title_today)
    route == Routes.MEDS -> stringResource(R.string.title_dependants)
    route == Routes.TRENDS -> stringResource(R.string.title_adherence)
    route == Routes.APPOINTMENTS -> stringResource(R.string.title_appointments)
    route == Routes.MORE -> stringResource(R.string.title_settings)
    route == Routes.NOTIFICATIONS -> stringResource(R.string.title_notifications)
    route == Routes.REFILL_FORECAST -> stringResource(R.string.title_refill_forecast)
    route == Routes.PHARMACY_FINDER -> stringResource(R.string.title_pharmacy)
    route == Routes.DOCUMENTS -> stringResource(R.string.title_documents)
    route == Routes.ADD_MEDICATION -> stringResource(R.string.title_add_medication)
    route?.startsWith("edit_medication") == true -> stringResource(R.string.title_edit_medication)
    route?.startsWith("edit_dependant") == true -> stringResource(R.string.title_edit_profile)
    route?.startsWith("dependant_profile") == true -> dependantName ?: stringResource(R.string.title_dependants)
    else -> stringResource(R.string.app_name)
}

