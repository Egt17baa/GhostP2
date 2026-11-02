package org.ghostp2.app.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.app.AppContainer
import org.ghostp2.app.BatteryOptimization
import org.ghostp2.app.R
import org.ghostp2.app.StorageState
import org.ghostp2.app.lock.LockScreen
import org.ghostp2.app.ui.chat.ChatScreen
import org.ghostp2.app.ui.chat.ChatViewModel
import org.ghostp2.app.ui.chat.PhotoContent
import org.ghostp2.app.ui.chat.PhotoViewerScreen
import org.ghostp2.app.ui.chat.PhotoViewerViewModel
import org.ghostp2.app.ui.contact.ContactDetailScreen
import org.ghostp2.app.ui.contact.ContactDetailViewModel
import org.ghostp2.app.ui.contacts.ContactRow
import org.ghostp2.app.ui.contacts.ContactsScreen
import org.ghostp2.app.ui.contacts.ContactsViewModel
import org.ghostp2.app.ui.contacts.openRoute
import org.ghostp2.app.ui.onboarding.OnboardingRoute
import org.ghostp2.app.ui.pairing.AddContactScreen
import org.ghostp2.app.ui.pairing.NameContactScreen
import org.ghostp2.app.ui.pairing.NameContactViewModel
import org.ghostp2.app.ui.pairing.PairingViewModel
import org.ghostp2.app.ui.settings.BridgesScreen
import org.ghostp2.app.ui.settings.LicensesScreen
import org.ghostp2.app.ui.settings.LicensesState
import org.ghostp2.app.ui.settings.SettingsScreen
import org.ghostp2.app.ui.settings.SettingsViewModel
import org.ghostp2.app.ui.settings.readLicenseFiles
import org.ghostp2.data.AppSettings
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.bridges.BridgeMode

/** Lo que la interfaz pide a la actividad: servicio, permiso de notificaciones y exención de batería. */
class ActivityActions(
    val activate: () -> Unit,
    val disconnect: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val requestNotifications: () -> Unit,
)

/**
 * Raíz de la interfaz. Sin comprobar el almacenamiento: lo comprueba y muestra la carga; si falla:
 * [StorageErrorScreen]; con los ajustes aún sin leer: solo la carga; con la app bloqueada: solo
 * [LockScreen] (ni la bienvenida ni el `NavHost` llegan a componerse); mientras `onboardingDone` sea
 * falso: la bienvenida; después, el `NavHost` (inicio en [Routes.CONTACTS]).
 *
 * [navController] es de la raíz, no de lo que se ve con el almacenamiento bien: al volver del bloqueo o
 * de un error, el `NavHost` sigue con la misma pila en vez de dejar la anterior (y sus ViewModel)
 * huérfana en memoria. Cuando el almacenamiento deja de estar bien (error, "Empezar de cero", "Borrar
 * todo") la pila se vacía hasta la lista: lo que hubiera abierto puede no existir ya.
 */
@Composable
fun GhostRoot(container: AppContainer, actions: ActivityActions, navController: NavHostController = rememberNavController()) {
    val storage by container.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (container.storage.value == StorageState.Unknown) container.checkStorage() }
    val storageOk = storage == StorageState.Ok
    LaunchedEffect(storageOk) {
        if (!storageOk && navController.currentBackStackEntry != null) navController.popBackStack(Routes.CONTACTS, inclusive = false)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = storage) {
            StorageState.Unknown -> Loading()
            StorageState.Ok -> ReadyRoot(container, actions, navController)
            StorageState.Corrupted, is StorageState.Unavailable, is StorageState.Failed ->
                Box(Modifier.safeDrawingPadding()) { StorageErrorScreen(container, state) }
        }
    }
}

/**
 * Almacenamiento comprobado: espera a los ajustes y después navega. Si el flujo de ajustes falla, se
 * vuelve a comprobar el almacenamiento: si sigue mal, la raíz pasa a [StorageErrorScreen] (y este flujo
 * deja de recogerse); si no, se reintenta, hasta [SETTINGS_ATTEMPTS] lecturas. Agotadas, se muestra
 * [StorageErrorScreen] con [StorageState.Failed] (nunca una carga sin fin) y "Reintentar" vuelve a
 * empezar; cada tanda agotada cuenta como un fallo de la racha (a la tercera se ofrece empezar de cero).
 */
@Composable
private fun ReadyRoot(container: AppContainer, actions: ActivityActions, navController: NavHostController) {
    var round by remember { mutableIntStateOf(0) }
    var exhaustedRounds by remember { mutableIntStateOf(0) }
    var exhausted by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var onboardingSaveFailed by remember { mutableStateOf(false) }
    val ui = rememberCoroutineScope()
    val settingsFlow = remember(container, round) {
        container.settings.settings
            .retryWhen { _, attempt ->
                attempt + 1 < SETTINGS_ATTEMPTS &&
                    container.checkStorage() == StorageState.Ok &&
                    run {
                        delay(SETTINGS_RETRY_MILLIS)
                        true
                    }
            }
            .catch {
                // Nunca se cierra la app; si el almacenamiento falla, ya lo muestra la raíz.
                if (container.storage.value == StorageState.Ok) {
                    exhaustedRounds++
                    exhausted = true
                }
            }
    }
    val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
    val locked by container.lock.locked.collectAsStateWithLifecycle()
    val missingDeviceLockNotice by container.lock.missingDeviceLockNotice.collectAsStateWithLifecycle()
    if (exhausted) {
        Box(Modifier.safeDrawingPadding()) {
            StorageErrorScreen(container, StorageState.Failed(exhaustedRounds), onRetry = {
                if (container.checkStorage() == StorageState.Ok) {
                    exhausted = false
                    round++
                }
            })
        }
        return
    }
    val current = settings
    if (current == null) {
        Loading()
        return
    }
    if (locked) {
        if (current.lockEnabled) {
            // El éxito del diálogo lo recibe el LockPrompt de la actividad (desbloquea container.lock).
            LockScreen(
                onNoDeviceLock = container::disableLockForMissingDeviceLock,
                deviceLockAvailable = container.deviceLockAvailable,
            )
        } else {
            // El bloqueo aún no ha leído los ajustes (falla cerrado) y está desactivado: solo la carga.
            Loading()
        }
        return
    }
    if (missingDeviceLockNotice) {
        AlertDialog(
            onDismissRequest = container.lock::missingDeviceLockNoticeShown,
            text = { Text(stringResource(R.string.lock_disabled_no_device_lock)) },
            confirmButton = {
                TextButton(onClick = container.lock::missingDeviceLockNoticeShown) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
    if (!current.onboardingDone) {
        // Tras "Borrar todo" la pila de navegación (fuera del bloqueo) seguiría en los ajustes: se vacía
        // hasta la lista para que, al terminar la bienvenida, se empiece por ella.
        LaunchedEffect(navController) {
            if (navController.currentBackStackEntry != null) navController.popBackStack(Routes.CONTACTS, inclusive = false)
        }
        OnboardingRoute(actions, saveFailed = onboardingSaveFailed, onFinish = {
            if (!finishing) {
                finishing = true
                onboardingSaveFailed = false
                ui.launch {
                    try {
                        onboardingSaveFailed = !finishOnboarding(container, actions)
                    } finally {
                        finishing = false
                    }
                }
            }
        })
        return
    }

    NavHost(navController = navController, startDestination = Routes.CONTACTS) {
        composable(Routes.CONTACTS) {
            val vm = ghostViewModel(container) { ContactsViewModel(it) }
            val state by vm.state.collectAsStateWithLifecycle()
            val deleteNotice by vm.deleteNotice.collectAsStateWithLifecycle()
            val status by container.engine.status.collectAsStateWithLifecycle()
            ContactsScreen(
                state = state,
                banner = { RootBanner(container, actions, bridged = current.bridgeMode != BridgeMode.NONE) },
                onOpen = dropUnlessResumedWith { row: ContactRow -> navController.open(openRoute(row)) },
                onAdd = dropUnlessResumed { navController.open(Routes.ADD) },
                onSettings = dropUnlessResumed { navController.open(Routes.SETTINGS) },
                onRetry = vm::retry,
                onDisconnect = actions.disconnect.takeIf { status == EngineStatus.Online },
                deleteNotice = deleteNotice,
                onDeleteNoticeShown = vm::deleteNoticeShown,
            )
        }
        composable(Routes.ADD) {
            // La invitación vive lo que este ViewModel: al salir (atrás o al emparejar) se cancela.
            val vm = ghostViewModel(container) { PairingViewModel(it) }
            val status by container.engine.status.collectAsStateWithLifecycle()
            val invitation by vm.invitation.collectAsStateWithLifecycle()
            val accept by vm.accept.collectAsStateWithLifecycle()
            val pasted by vm.pasted.collectAsStateWithLifecycle()
            val startFailed by container.startFailed.collectAsStateWithLifecycle()
            val paired by vm.pairedContactId.collectAsStateWithLifecycle()
            LaunchedEffect(paired) {
                paired?.let { id ->
                    // A nombrar al contacto, sin dejar "Añadir" en la pila.
                    navController.navigate(Routes.name(id)) { popUpTo(Routes.ADD) { inclusive = true } }
                }
            }
            AddContactScreen(
                status = status,
                invitation = invitation,
                accept = accept,
                pasted = pasted,
                onPastedChange = vm::setPasted,
                onCreateInvitation = vm::createInvitation,
                onAccept = vm::accept,
                // Si el almacenamiento falla, la raíz pasa a StorageErrorScreen (el aviso visible).
                onActivate = { ui.launch { if (container.checkStorage() == StorageState.Ok) actions.activate() } },
                onBack = dropUnlessResumed { navController.popBackStack() },
                startFailed = startFailed,
                bridged = current.bridgeMode != BridgeMode.NONE,
            )
        }
        composable(Routes.NAME) { entry ->
            val id = entry.idArg()
            val vm = ghostViewModel(container) { NameContactViewModel(it, id) }
            val safetyNumber by vm.safetyNumber.collectAsStateWithLifecycle()
            val error by vm.error.collectAsStateWithLifecycle()
            val saved by vm.saved.collectAsStateWithLifecycle()
            val saving by vm.saving.collectAsStateWithLifecycle()
            val name by vm.name.collectAsStateWithLifecycle()
            val verified by vm.verified.collectAsStateWithLifecycle()
            LaunchedEffect(saved) { if (saved) navController.leaveNaming(id) }
            NameContactScreen(
                safetyNumber = safetyNumber,
                error = error,
                name = name,
                onNameChange = vm::setName,
                verified = verified,
                onVerifiedChange = vm::setVerified,
                saving = saving,
                onSave = { vm.save(name, verified) },
                onLater = dropUnlessResumed { navController.leaveNaming(id) },
            )
        }
        composable(Routes.CONTACT) { entry ->
            val vm = ghostViewModel(container) { ContactDetailViewModel(it, entry.idArg()) }
            val state by vm.state.collectAsStateWithLifecycle()
            val gone by vm.gone.collectAsStateWithLifecycle()
            val busy by vm.busy.collectAsStateWithLifecycle()
            val renameDraft by vm.renameDraft.collectAsStateWithLifecycle()
            val renameError by vm.renameError.collectAsStateWithLifecycle()
            val actionError by vm.actionError.collectAsStateWithLifecycle()
            val deleteResult by vm.deleteResult.collectAsStateWithLifecycle()
            val lastName by vm.lastName.collectAsStateWithLifecycle()
            // Contacto que ya no existe sin borrarlo desde aquí (p. ej. nos borró él): a la lista.
            LaunchedEffect(gone) { if (gone) navController.popBackStack(Routes.CONTACTS, inclusive = false) }
            ContactDetailScreen(
                state = state,
                busy = busy,
                renameDraft = renameDraft,
                renameError = renameError,
                actionError = actionError,
                deleteResult = deleteResult,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onStartRename = vm::startRename,
                onRenameDraftChange = vm::setRenameDraft,
                onRename = { renameDraft?.let(vm::rename) },
                onCancelRename = vm::cancelRename,
                onSetVerified = vm::setVerified,
                // Emparejar de nuevo con una invitación nueva; el detalle se queda detrás en la pila.
                onRepair = dropUnlessResumed { navController.open(Routes.ADD) },
                onClearChat = vm::clearChat,
                onDelete = vm::delete,
                onDeleteDone = { navController.popBackStack(Routes.CONTACTS, inclusive = false) },
                lastName = lastName,
            )
        }
        composable(Routes.CHAT) { entry ->
            val id = entry.idArg()
            // El borrador vive en este ViewModel (en memoria): sobrevive a girar el móvil, no a que el sistema mate la app.
            val vm = ghostViewModel(container) { ChatViewModel(it, id) }
            val state by vm.state.collectAsStateWithLifecycle()
            val gone by vm.gone.collectAsStateWithLifecycle()
            val preparingPhotos by vm.preparingPhotos.collectAsStateWithLifecycle()
            val photoError by vm.photoErrors.collectAsStateWithLifecycle()
            // Contacto borrado (desde el detalle o por él): a la lista.
            LaunchedEffect(gone) { if (gone) navController.popBackStack(Routes.CONTACTS, inclusive = false) }
            // Con el chat a la vista, sus mensajes nuevos no se notifican.
            LifecycleResumeEffect(id) {
                container.chatShown(id)
                onPauseOrDispose { container.chatHidden(id) }
            }
            ChatScreen(
                state = state,
                draft = vm.draft,
                onDraftChange = { vm.draft = it },
                onSend = { vm.send() },
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenContact = dropUnlessResumed { navController.open(Routes.contact(id)) },
                onClear = vm::clear,
                photoButton = { enabled ->
                    // Selector de fotos del sistema: solo imágenes y sin permiso de galería. La Uri no se guarda.
                    val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri -> uri?.let(vm::pickPhoto) }
                    IconButton(onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, enabled = enabled) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.chat_photo_send))
                    }
                },
                photoContent = { item ->
                    PhotoContent(item, vm.bitmaps, onOpen = dropUnlessResumed { navController.open(Routes.photo(id, item.id)) })
                },
                preparingPhotos = preparingPhotos,
                photoError = photoError,
                onPhotoErrorShown = vm::photoErrorShown,
            )
        }
        composable(Routes.PHOTO) { entry ->
            val id = entry.idArg()
            val item = entry.idArg("item")
            val vm = ghostViewModel(container) { PhotoViewerViewModel(it, id, item) }
            val photo by vm.photo.collectAsStateWithLifecycle()
            val saveResult by vm.saveResult.collectAsStateWithLifecycle()
            val resolver = LocalContext.current.contentResolver
            PhotoViewerScreen(
                photo = photo,
                saveResult = saveResult,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onSaveTo = { target -> vm.save(resolver, target) },
                onSaveResultShown = vm::saveResultShown,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRoute(
                container,
                actions,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenBridges = dropUnlessResumed { navController.open(Routes.BRIDGES) },
                onOpenLicenses = dropUnlessResumed { navController.open(Routes.LICENSES) },
            )
        }
        composable(Routes.BRIDGES) {
            BridgesRoute(container, onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable(Routes.LICENSES) {
            LicensesRoute(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
    // Tras "Desconectar al salir de la app" (R29), la lista con "Activar", como tras "Desconectar": el
    // chat o la foto que se veía ya no está en memoria. Tras el NavHost: su pila ya está restaurada.
    val returnHome by container.autoDisconnect.returnHome.collectAsStateWithLifecycle()
    if (returnHome) {
        LaunchedEffect(Unit) {
            if (navController.currentBackStackEntry != null) navController.popBackStack(Routes.CONTACTS, inclusive = false)
            container.autoDisconnect.returnedHome()
        }
    }
}

@Composable
private fun SettingsRoute(
    container: AppContainer,
    actions: ActivityActions,
    onBack: () -> Unit,
    onOpenBridges: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    val vm = ghostViewModel(container) { SettingsViewModel(it) }
    val settings by vm.state.collectAsStateWithLifecycle()
    val lockNeedsDeviceLock by vm.lockNeedsDeviceLock.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    val wiping by vm.wiping.collectAsStateWithLifecycle()
    val wipeFailed by vm.wipeFailed.collectAsStateWithLifecycle()
    val myOnion by container.engine.myOnion.collectAsStateWithLifecycle()
    val lastDiagnostic by container.lastDiagnostic.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Se vuelve a leer en cada onResume (al volver del diálogo del sistema).
    var batteryIgnoring by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    LifecycleResumeEffect(context) {
        batteryIgnoring = BatteryOptimization.isIgnoring(context)
        onPauseOrDispose { }
    }
    val version = remember(context) { appVersion(context) }
    SettingsScreen(
        settings = settings,
        batteryIgnoring = batteryIgnoring,
        manufacturerHint = BatteryOptimization.manufacturerHint(Build.MANUFACTURER),
        version = version,
        myOnion = myOnion,
        lastDiagnostic = lastDiagnostic,
        lockNeedsDeviceLock = lockNeedsDeviceLock,
        saveFailed = saveFailed,
        wiping = wiping,
        wipeFailed = wipeFailed,
        onBack = onBack,
        onSetLock = { vm.setLock(it) },
        onSetLockAfter = vm::setLockAfter,
        onSetHideNames = vm::setHideNames,
        onSetMaxPhotoSide = vm::setMaxPhotoSide,
        onRequestBattery = actions.requestBatteryExemption,
        onSetStartOnBoot = vm::setStartOnBoot,
        onSetDisconnectAfter = vm::setDisconnectAfter,
        onWipeAll = vm::wipeAll,
        onOpenBridges = onOpenBridges,
        onOpenLicenses = onOpenLicenses,
    )
}

/** Ajustes › Acerca de › «Licencias de terceros»: los archivos de la APK, leídos fuera del hilo principal y sin red. */
@Composable
private fun LicensesRoute(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val state by produceState<LicensesState>(LicensesState.Loading, assets) {
        value = try {
            LicensesState.Loaded(withContext(Dispatchers.IO) { readLicenseFiles(assets) })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LicensesState.Failed
        }
    }
    LicensesScreen(state, onBack)
}

/** Ajustes › Puentes (R30), con su propio [SettingsViewModel] (el cambio pendiente de reconexión vive en él). */
@Composable
private fun BridgesRoute(container: AppContainer, onBack: () -> Unit) {
    val vm = ghostViewModel(container) { SettingsViewModel(it) }
    val settings by vm.state.collectAsStateWithLifecycle()
    val saveFailed by vm.saveFailed.collectAsStateWithLifecycle()
    BridgesScreen(
        settings = settings,
        saveFailed = saveFailed,
        onBack = onBack,
        onSave = vm::setBridges,
        onConfirmReconnect = vm::confirmReconnect,
        onCancelReconnect = vm::cancelReconnect,
        onDone = onBack,
    )
}

/** `versionName` del paquete; vacío si no se puede leer. */
private fun appVersion(context: Context): String =
    try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        info.versionName.orEmpty()
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

/**
 * Sale de nombrar al contacto [id] hacia su chat. Se vacía la pila hasta la lista (nombrar y, si se
 * llegó desde "Volver a emparejar", el detalle y el chat viejos): queda lista → chat. Una segunda
 * llamada con ese chat ya arriba no apila otro (`launchSingleTop`); en "Más tarde", `dropUnlessResumed`
 * descarta además las pulsaciones durante la transición.
 */
private fun NavController.leaveNaming(id: String) {
    navigate(Routes.chat(id)) {
        popUpTo(Routes.CONTACTS)
        launchSingleTop = true
    }
}

/** Abre [route] sin duplicarla si ya es la de arriba (`launchSingleTop`). */
private fun NavController.open(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Como `dropUnlessResumed` de lifecycle, para clics con un argumento: solo actúa con la pantalla
 * reanudada, así un doble toque (el segundo llega con la navegación ya en marcha) abre una sola pantalla.
 */
@Composable
private fun <T> dropUnlessResumedWith(block: (T) -> Unit): (T) -> Unit {
    val owner = LocalLifecycleOwner.current
    return { value -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block(value) }
}

/**
 * [EngineBanner] del motor del contenedor. "Activar" y "Reintentar" comprueban antes el almacenamiento:
 * si falla, la raíz muestra [StorageErrorScreen] en lugar de arrancar. [bridged]: el ajuste «Puentes» no es «No» (R30).
 */
@Composable
private fun RootBanner(container: AppContainer, actions: ActivityActions, bridged: Boolean) {
    val status by container.engine.status.collectAsStateWithLifecycle()
    val startFailed by container.startFailed.collectAsStateWithLifecycle()
    val ui = rememberCoroutineScope()
    EngineBanner(
        status = status,
        startFailed = startFailed,
        onActivate = { ui.launch { if (container.checkStorage() == StorageState.Ok) actions.activate() } },
        onDisconnect = actions.disconnect,
        bridged = bridged,
    )
}

/**
 * "Activar GhostP2" de la bienvenida: primero la marca como hecha y solo después activa. Si no se puede
 * guardar, no se activa y se vuelve a comprobar el almacenamiento: si falla, la raíz muestra
 * [StorageErrorScreen]; si no, devuelve falso y la bienvenida muestra el error junto al botón.
 */
private suspend fun finishOnboarding(container: AppContainer, actions: ActivityActions): Boolean {
    try {
        container.settings.update { it.copy(onboardingDone = true) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // El almacenamiento falla: la raíz pasa a la pantalla de error y este aviso ya no se ve.
        container.checkStorage()
        return false
    }
    actions.activate()
    return true
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

private const val SETTINGS_ATTEMPTS = 3
private const val SETTINGS_RETRY_MILLIS = 1_000L
