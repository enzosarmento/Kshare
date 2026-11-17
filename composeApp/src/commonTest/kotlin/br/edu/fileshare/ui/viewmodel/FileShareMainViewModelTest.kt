package br.edu.fileshare.ui.viewmodel

import br.edu.fileshare.network.discovery.DiscoveredPeer
import br.edu.fileshare.network.discovery.PeerOrigin
import br.edu.fileshare.protocol.FsapConstants
import br.edu.fileshare.protocol.model.DeviceIdentity
import br.edu.fileshare.protocol.model.DeviceType
import br.edu.fileshare.protocol.model.PlatformType
import br.edu.fileshare.protocol.statemachine.LocalNodeState
import br.edu.fileshare.protocol.statemachine.PeerConnectionState
import br.edu.fileshare.storage.FileSource
import br.edu.fileshare.storage.InMemoryFileSource
import br.edu.fileshare.storage.StoredFileDescriptor
import br.edu.fileshare.ui.FileShareResponsiveBreakpoints
import br.edu.fileshare.ui.format.ByteFormatter
import kotlin.test.assertFalse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileShareMainViewModelTest {

    private class FakeRuntimePort : FileShareRuntimePort {
        override val localDeviceId: String = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        override val initialAlias: String = "Desktop-Lab"
        override val initialPort: Int = FsapConstants.DEFAULT_HTTP_PORT
        override val saveDirectoryLabel: String = "/tmp/downloads"

        val peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
        val receivedFlow = MutableStateFlow<List<StoredFileDescriptor>>(emptyList())
        val addressesFlow = MutableStateFlow(listOf("192.168.1.42"))

        override val peers: StateFlow<List<DiscoveredPeer>> = peersFlow
        override val receivedFiles: StateFlow<List<StoredFileDescriptor>> = receivedFlow
        override val localAddresses: StateFlow<List<String>> = addressesFlow
        val externalStateFlow = MutableStateFlow(LocalNodeState.STOPPED)
        val externalPortFlow = MutableStateFlow(FsapConstants.DEFAULT_HTTP_PORT)
        override val externalNodeState: StateFlow<LocalNodeState> = externalStateFlow
        override val externalBoundPort: StateFlow<Int> = externalPortFlow
        val externalAliasFlow = MutableStateFlow(initialAlias)
        override val externalLocalAlias: StateFlow<String> = externalAliasFlow
        val externalSaveDirFlow = MutableStateFlow(saveDirectoryLabel)
        override val externalSaveDirectoryLabel: StateFlow<String> = externalSaveDirFlow
        val isCustomSaveDirFlow = MutableStateFlow(false)
        override val isCustomSaveDirectory: StateFlow<Boolean> = isCustomSaveDirFlow

        var failNextStart: Boolean = false
        var resolvedPortOverride: Int? = null
        var pickedFilesQueue: List<FileSource> = emptyList()
        var pickedSaveDirectoryResult: String? = null
        var failNextPickSaveDirectory: Boolean = false
        var lastStartedAlias: String? = null
        var lastStartedPort: Int? = null
        var stopCalls: Int = 0
        var refreshCalls: Int = 0
        var lastSentTransfer: Pair<DiscoveredPeer, List<FileSource>>? = null

        override suspend fun startNode(alias: String, port: Int): Result<Int> {
            lastStartedAlias = alias
            lastStartedPort = port
            return if (failNextStart) {
                failNextStart = false
                Result.failure(IllegalStateException("Porta em uso"))
            } else {
                Result.success(resolvedPortOverride ?: port)
            }
        }

        override suspend fun stopNode() {
            stopCalls++
        }

        override suspend fun refreshDiscovery(): Result<Unit> {
            refreshCalls++
            return Result.success(Unit)
        }

        override suspend fun pickFilesToSend(): List<FileSource> = pickedFilesQueue

        override suspend fun pickSaveDirectory(): String? {
            if (failNextPickSaveDirectory) {
                failNextPickSaveDirectory = false
                error("Diretorio protegido")
            }
            val next = pickedSaveDirectoryResult
            if (next != null) {
                isCustomSaveDirFlow.value = true
                externalSaveDirFlow.value = next
            }
            return next
        }

        override suspend fun resetSaveDirectory(): String {
            isCustomSaveDirFlow.value = false
            externalSaveDirFlow.value = saveDirectoryLabel
            return saveDirectoryLabel
        }

        override suspend fun sendFiles(peer: DiscoveredPeer, sources: List<FileSource>): Result<String> {
            lastSentTransfer = peer to sources
            return Result.success("7c9e6679-7425-40de-944b-e07fc1f90ae7")
        }
    }

    private fun samplePeer(id: String = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", alias: String = "Celular"): DiscoveredPeer =
        DiscoveredPeer(
            identity = DeviceIdentity(
                deviceId = id,
                alias = alias,
                deviceType = DeviceType.MOBILE,
                platform = PlatformType.ANDROID,
                httpPort = 53317,
            ),
            ipAddress = "192.168.1.99",
            port = 53317,
            connectionState = PeerConnectionState.CONNECTED,
            origin = PeerOrigin.MANUAL_IP,
            lastSeenEpochMillis = 100L,
            firstSeenEpochMillis = 100L,
        )

    @Test
    fun `ciclo de vida do no local transita entre STOPPED, FAULTED, AVAILABLE e STOPPED`() = runTest {
        val runtime = FakeRuntimePort()
        runtime.failNextStart = true
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        runCurrent()

        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)

        // Falha de bind leva para FAULTED e exibe banner
        vm.startNodeInternal()
        assertEquals(LocalNodeState.FAULTED, vm.uiState.value.nodeState)
        assertNotNull(vm.uiState.value.statusBanner)

        // Retry bem-sucedido leva para AVAILABLE e limpa banner
        vm.startNodeInternal()
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
        assertNull(vm.uiState.value.statusBanner)

        // Stop retorna para STOPPED
        vm.stopNodeInternal()
        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)
        assertEquals(1, runtime.stopCalls)
    }

    @Test
    fun `navegacao entre abas, selecao de arquivos e envio para par conectado funcionam em UDF`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        vm.selectTab(MainTab.RECEIVED)
        assertEquals(MainTab.RECEIVED, vm.uiState.value.activeTab)
        vm.selectTab(MainTab.SEND)

        // Seletor adiciona arquivos e evita duplicata exata nome+tamanho
        val f1 = InMemoryFileSource("a.pdf", ByteArray(100))
        val f2 = InMemoryFileSource("b.png", ByteArray(250))
        runtime.pickedFilesQueue = listOf(f1, f2, f1)
        vm.pickFilesInternal()
        assertEquals(2, vm.uiState.value.selectedFiles.size)
        assertEquals(350L, vm.uiState.value.totalSelectedBytes)

        // Aparece peer unico -> auto-selecionado
        val peer = samplePeer()
        runtime.peersFlow.value = listOf(peer)
        runCurrent()
        assertEquals(peer.identity.deviceId, vm.uiState.value.selectedPeerDeviceId)
        assertTrue(vm.uiState.value.canSendFiles)

        // Envio move para aba Transferencias e limpa fila de envio
        vm.sendSelectedFilesInternal()
        assertNotNull(runtime.lastSentTransfer)
        assertEquals(2, runtime.lastSentTransfer?.second?.size)
        assertEquals(MainTab.TRANSFERS, vm.uiState.value.activeTab)
        assertTrue(vm.uiState.value.selectedFiles.isEmpty())
    }

    @Test
    fun `validacao de alias e porta nas configuracoes impede reinicio invalido`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        vm.updateSettingsDraft("", "53317")
        vm.applySettingsAndRestartInternal()
        assertNotNull(vm.uiState.value.settingsError)

        vm.updateSettingsDraft("Meu-PC", "80")
        vm.applySettingsAndRestartInternal()
        assertNotNull(vm.uiState.value.settingsError)

        vm.updateSettingsDraft("Desktop-Sala3", "54000")
        vm.applySettingsAndRestartInternal()
        assertNull(vm.uiState.value.settingsError)
        assertEquals("Desktop-Sala3", runtime.lastStartedAlias)
        assertEquals(54000, runtime.lastStartedPort)
        assertEquals(54000, vm.uiState.value.boundHttpPort)
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
    }

    @Test
    fun `ByteFormatter formata bytes taxas ETA extensoes e metadados corretamente`() {
        assertEquals("512 B", ByteFormatter.formatBytes(512))
        assertEquals("1.5 KB", ByteFormatter.formatBytes(1536))
        assertEquals("2.50 MB/s", ByteFormatter.formatSpeed(2_621_440L))
        assertEquals("--", ByteFormatter.formatEta(null))
        assertEquals("45s", ByteFormatter.formatEta(45))
        assertEquals("2m 05s", ByteFormatter.formatEta(125))

        assertEquals("PDF", ByteFormatter.formatFileExtension("relatorio.final.pdf"))
        assertEquals("ARQ", ByteFormatter.formatFileExtension("README"))
        assertEquals("ARQ", ByteFormatter.formatFileExtension("arquivo.longaext"))

        assertEquals("Computador", ByteFormatter.formatDeviceType(DeviceType.DESKTOP))
        assertEquals("Smartphone", ByteFormatter.formatDeviceType(DeviceType.MOBILE))
        assertEquals("Tablet", ByteFormatter.formatDeviceType(DeviceType.TABLET))
        assertEquals("Servidor", ByteFormatter.formatDeviceType(DeviceType.SERVER))

        assertEquals("Linux", ByteFormatter.formatPlatform(PlatformType.LINUX))
        assertEquals("Windows", ByteFormatter.formatPlatform(PlatformType.WINDOWS))
        assertEquals("Android", ByteFormatter.formatPlatform(PlatformType.ANDROID))

        assertTrue(ByteFormatter.isErrorMessage("Falha ao iniciar servico de rede"))
        assertTrue(ByteFormatter.isErrorMessage("Erro ao selecionar arquivos"))
        assertTrue(ByteFormatter.isErrorMessage("Porta inválida."))
        assertTrue(ByteFormatter.isErrorMessage("Serviço indisponível"))
        assertTrue(ByteFormatter.isErrorMessage("Não foi possível conectar"))
        assertFalse(ByteFormatter.isErrorMessage("Envio iniciado para Pixel-9."))
        assertEquals("Transferências", MainTab.TRANSFERS.title)
        assertEquals("Configurações", MainTab.SETTINGS.title)
    }

    @Test
    fun `refreshPeers dispara descoberta e atualiza selecao quando par expira`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        val peer = samplePeer(id = "550e8400-e29b-41d4-a716-446655440999", alias = "Android-LAN")
            .copy(origin = PeerOrigin.MULTICAST, connectionState = PeerConnectionState.DISCOVERED)
        runtime.peersFlow.value = listOf(peer)
        runCurrent()
        assertEquals(peer.identity.deviceId, vm.uiState.value.selectedPeerDeviceId)

        vm.refreshPeers()
        runCurrent()
        assertEquals(1, runtime.refreshCalls)
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)

        // Par transita para STALE mantendo selecao
        runtime.peersFlow.value = listOf(peer.copy(connectionState = PeerConnectionState.STALE))
        runCurrent()
        assertEquals(PeerConnectionState.STALE, vm.uiState.value.selectedPeer?.connectionState)

        // Expirado -> selecao limpa automaticamente
        runtime.peersFlow.value = emptyList()
        runCurrent()
        assertNull(vm.uiState.value.selectedPeerDeviceId)
    }

    @Test
    fun `reconciliacao externa de estado acompanha parada via notificacao e reconexao`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        runCurrent()
        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)

        // Servico Android ja ativo ao abrir Activity -> UI assume AVAILABLE e porta vinculada
        runtime.externalPortFlow.value = 54012
        runtime.externalStateFlow.value = LocalNodeState.AVAILABLE
        runCurrent()
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
        assertEquals(54012, vm.uiState.value.boundHttpPort)

        // Encerramento via acao na notificacao -> UI volta para STOPPED com banner informativo
        runtime.externalStateFlow.value = LocalNodeState.STOPPED
        runCurrent()
        assertEquals(LocalNodeState.STOPPED, vm.uiState.value.nodeState)
        assertNotNull(vm.uiState.value.statusBanner)
    }

    @Test
    fun `fallback automatico de porta atualiza boundHttpPort, portDraft e exibe banner informativo`() = runTest {
        val runtime = FakeRuntimePort()
        runtime.resolvedPortOverride = 53319
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        runCurrent()

        vm.startNodeInternal()
        assertEquals(LocalNodeState.AVAILABLE, vm.uiState.value.nodeState)
        assertEquals(53319, vm.uiState.value.boundHttpPort)
        assertEquals("53319", vm.uiState.value.portDraft)
        assertEquals(
            "Porta TCP 53317 em uso; alternado automaticamente para a porta livre 53319.",
            vm.uiState.value.statusBanner,
        )

        runtime.resolvedPortOverride = 54001
        vm.updateSettingsDraft("Desktop-Lab", "54000")
        vm.applySettingsAndRestartInternal()
        assertEquals(54001, vm.uiState.value.boundHttpPort)
        assertEquals("54001", vm.uiState.value.portDraft)
        assertEquals(
            "Configurações aplicadas (HTTP). Porta 54000 em uso; alternado automaticamente para 54001.",
            vm.uiState.value.statusBanner,
        )
    }

    @Test
    fun `alteracao pendente em aliasDraft aplica novo alias automaticamente antes do envio e sincroniza alias externo`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        vm.startNodeInternal()
        runCurrent()

        // Alias atualizado pelo servico em segundo plano reflete em localAlias e aliasDraft
        runtime.externalAliasFlow.value = "Desktop-Servico"
        runCurrent()
        assertEquals("Desktop-Servico", vm.uiState.value.localAlias)
        assertEquals("Desktop-Servico", vm.uiState.value.aliasDraft)

        // Usuario digita novo alias nas configuracoes mas vai enviar direto sem clicar em Aplicar
        val peer = samplePeer()
        runtime.peersFlow.value = listOf(peer)
        runtime.pickedFilesQueue = listOf(InMemoryFileSource("nota.txt", "abc".encodeToByteArray()))
        vm.pickFilesInternal()
        vm.selectTab(MainTab.SETTINGS)
        vm.updateSettingsDraft("Desktop-Atualizado", FsapConstants.DEFAULT_HTTP_PORT.toString())
        vm.selectTab(MainTab.SEND)
        runCurrent()

        vm.sendSelectedFilesInternal()
        assertEquals("Desktop-Atualizado", vm.uiState.value.localAlias)
        assertEquals("Desktop-Atualizado", runtime.lastStartedAlias)
        assertNotNull(runtime.lastSentTransfer)
    }

    @Test
    fun `escolha de pasta de destino atualiza saveDirectoryLabel, banner e sincroniza fluxo externo`() = runTest {
        val runtime = FakeRuntimePort()
        val vm = FileShareMainViewModel(runtime, backgroundScope)
        runCurrent()

        assertEquals("/tmp/downloads", vm.uiState.value.saveDirectoryLabel)

        // Cancelamento (null) preserva caminho atual sem mensagem de erro
        runtime.pickedSaveDirectoryResult = null
        vm.pickSaveDirectoryInternal()
        assertEquals("/tmp/downloads", vm.uiState.value.saveDirectoryLabel)
        assertNull(vm.uiState.value.statusBanner)

        // Escolha valida atualiza saveDirectoryLabel e exibe banner de confirmacao
        runtime.pickedSaveDirectoryResult = "/home/aluno/Documentos/KShare"
        vm.pickSaveDirectoryInternal()
        runCurrent()
        assertEquals("/home/aluno/Documentos/KShare", vm.uiState.value.saveDirectoryLabel)
        assertEquals(
            "Pasta de recebimento alterada para: /home/aluno/Documentos/KShare",
            vm.uiState.value.statusBanner,
        )

        // Falha de I/O exibe banner de erro sem perder caminho anterior
        runtime.failNextPickSaveDirectory = true
        vm.pickSaveDirectoryInternal()
        assertEquals("/home/aluno/Documentos/KShare", vm.uiState.value.saveDirectoryLabel)
        assertTrue(ByteFormatter.isErrorMessage(vm.uiState.value.statusBanner.orEmpty()))

        // Atualizacao externa (ex.: sincronizada do servico em segundo plano) reflete na UI
        runtime.externalSaveDirFlow.value = "/mnt/sd/Recebidos"
        runCurrent()
        assertEquals("/mnt/sd/Recebidos", vm.uiState.value.saveDirectoryLabel)
        assertTrue(vm.uiState.value.hasCustomSaveDirectory)

        // Restaurar padrao volta ao diretorio padrao e limpa flag hasCustomSaveDirectory
        vm.resetSaveDirectoryInternal()
        runCurrent()
        assertEquals("/tmp/downloads", vm.uiState.value.saveDirectoryLabel)
        assertFalse(vm.uiState.value.hasCustomSaveDirectory)
        assertEquals(
            "Pasta padrão restaurada: /tmp/downloads",
            vm.uiState.value.statusBanner,
        )
    }

    @Test
    fun `pontos de quebra responsivos distinguem smartphone retrato de tablet e desktop`() {
        assertFalse(FileShareResponsiveBreakpoints.isWideLayout(360))
        assertFalse(FileShareResponsiveBreakpoints.isWideLayout(412))
        assertFalse(FileShareResponsiveBreakpoints.isWideLayout(599))
        assertTrue(FileShareResponsiveBreakpoints.isWideLayout(600))
        assertTrue(FileShareResponsiveBreakpoints.isWideLayout(840))
        assertTrue(FileShareResponsiveBreakpoints.isWideLayout(1040))
    }
}

