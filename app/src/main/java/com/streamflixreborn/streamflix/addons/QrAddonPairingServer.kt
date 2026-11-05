package com.streamflixreborn.streamflix.addons

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.streamflixreborn.streamflix.profiles.TvCustomAddonEntity
import com.streamflixreborn.streamflix.utils.QrUtils
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface QrPairingStatus {
    data object Idle : QrPairingStatus
    data object Starting : QrPairingStatus
    data class WaitingConnection(
        val serverUrl: String,
        val qrBitmap: Bitmap,
        val statusMessage: String = "Esperando conexión...",
    ) : QrPairingStatus

    data class ValidatingManifest(
        val serverUrl: String,
        val qrBitmap: Bitmap,
        val statusMessage: String = "Validando manifest.json...",
    ) : QrPairingStatus

    data class Installed(
        val addon: TvCustomAddonEntity,
    ) : QrPairingStatus

    data class Error(
        val message: String,
    ) : QrPairingStatus
}

/**
 * Embedded local HTTP server powered by Ktor CIO (`8080`) that pairs with a user's mobile phone
 * over the home Wi-Fi network via QR code (`ZXing`) to validate and install Stremio `manifest.json` add-ons.
 */
class QrAddonPairingServer(
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "QrAddonPairingServer"
        const val DEFAULT_PORT = 8080
        private val CANDIDATE_PORTS = listOf(DEFAULT_PORT, 8081, 8082, 8088)
    }

    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private var engine: ApplicationEngine? = null

    private val _status = MutableStateFlow<QrPairingStatus>(QrPairingStatus.Idle)
    val status: StateFlow<QrPairingStatus> = _status.asStateFlow()

    fun startPairingSession() {
        serverScope.launch {
            lifecycleMutex.withLock {
                stopInternalLocked()
                _status.value = QrPairingStatus.Starting

                val localIp = LocalNetworkIpResolver.resolveLocalLanIpv4(appContext)
                if (localIp.isNullOrBlank()) {
                    _status.value = QrPairingStatus.Error(
                        "Conecta tu Android TV a una red Wi-Fi o Ethernet local para generar el código QR."
                    )
                    return@withLock
                }

                var boundPort: Int? = null
                var startedEngine: ApplicationEngine? = null

                for (candidatePort in CANDIDATE_PORTS) {
                    val serverUrl = "http://$localIp:$candidatePort"
                    val qrBitmap = QrUtils.generateOrNull(serverUrl, size = 640) ?: continue

                    val attempt = runCatching {
                        embeddedServer(
                            factory = CIO,
                            port = candidatePort,
                            host = "0.0.0.0",
                        ) {
                            routing {
                                get("/") {
                                    val current = _status.value
                                    if (current is QrPairingStatus.WaitingConnection) {
                                        _status.value = current.copy(
                                            statusMessage = "Móvil conectado · Esperando URL del manifiesto..."
                                        )
                                    }
                                    call.respondText(
                                        text = buildMobilePortalHtml(),
                                        contentType = ContentType.Text.Html,
                                        status = HttpStatusCode.OK,
                                    )
                                }

                                post("/install") {
                                    val params = runCatching { call.receiveParameters() }.getOrNull()
                                    val manifestUrl = params?.get("manifestUrl")?.trim().orEmpty()

                                    if (manifestUrl.isBlank()) {
                                        call.respondText(
                                            text = buildMobilePortalHtml(
                                                errorBanner = "Por favor, introduce una URL válida de manifest.json."
                                            ),
                                            contentType = ContentType.Text.Html,
                                            status = HttpStatusCode.BadRequest,
                                        )
                                        return@post
                                    }

                                    _status.value = QrPairingStatus.ValidatingManifest(
                                        serverUrl = serverUrl,
                                        qrBitmap = qrBitmap,
                                        statusMessage = "Validando manifest.json...",
                                    )

                                    val result = CustomAddonRepository.validateAndInstallAddon(
                                        rawManifestUrl = manifestUrl,
                                        context = appContext,
                                    )

                                    result.fold(
                                        onSuccess = { savedAddon ->
                                            call.respondText(
                                                text = buildSuccessHtml(savedAddon.name),
                                                contentType = ContentType.Text.Html,
                                                status = HttpStatusCode.OK,
                                            )
                                            _status.value = QrPairingStatus.Installed(savedAddon)
                                            // Stop Ktor CIO server automatically after flushing success response
                                            serverScope.launch {
                                                delay(350L)
                                                stopPairingSession()
                                            }
                                        },
                                        onFailure = { err ->
                                            val reason = err.localizedMessage
                                                ?: "No se pudo validar el archivo manifest.json."
                                            _status.value = QrPairingStatus.WaitingConnection(
                                                serverUrl = serverUrl,
                                                qrBitmap = qrBitmap,
                                                statusMessage = "Error de validación · Inténtalo de nuevo desde tu móvil",
                                            )
                                            call.respondText(
                                                text = buildMobilePortalHtml(
                                                    previousValue = manifestUrl,
                                                    errorBanner = reason,
                                                ),
                                                contentType = ContentType.Text.Html,
                                                status = HttpStatusCode.BadRequest,
                                            )
                                        },
                                    )
                                }
                            }
                        }.start(wait = false)
                    }.onFailure { err ->
                        Log.w(TAG, "Port $candidatePort unavailable: ${err.message}")
                    }.getOrNull()

                    if (attempt != null) {
                        boundPort = candidatePort
                        startedEngine = attempt
                        _status.value = QrPairingStatus.WaitingConnection(
                            serverUrl = serverUrl,
                            qrBitmap = qrBitmap,
                            statusMessage = "Esperando conexión...",
                        )
                        break
                    }
                }

                if (startedEngine == null || boundPort == null) {
                    _status.value = QrPairingStatus.Error(
                        "No fue posible iniciar el servidor local de emparejamiento."
                    )
                } else {
                    engine = startedEngine
                    Log.i(TAG, "Ktor CIO QR server listening on http://$localIp:$boundPort")
                }
            }
        }
    }

    fun stopPairingSession() {
        serverScope.launch {
            lifecycleMutex.withLock {
                stopInternalLocked()
            }
        }
    }

    private fun stopInternalLocked() {
        val activeEngine = engine ?: return
        engine = null
        runCatching {
            activeEngine.stop(250L, 600L)
        }.onFailure {
            Log.w(TAG, "Error stopping Ktor CIO server: ${it.message}")
        }
    }

    private fun buildMobilePortalHtml(
        previousValue: String = "",
        errorBanner: String? = null,
    ): String {
        val escapedValue = escapeHtml(previousValue)
        val errorBlock = if (!errorBanner.isNullOrBlank()) {
            """<div class="alert error">${escapeHtml(errorBanner)}</div>"""
        } else {
            ""
        }

        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
                <title>Instalar Add-on · Streamflix TV</title>
                <style>
                    :root {
                        color-scheme: dark;
                    }
                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                        font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "Segoe UI", Roboto, sans-serif;
                    }
                    body {
                        min-height: 100vh;
                        background: radial-gradient(circle at top, #181d2c 0%, #06070b 70%);
                        color: #f6f7fb;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .card {
                        width: 100%;
                        max-width: 440px;
                        background: rgba(24, 27, 36, 0.88);
                        border: 1px solid rgba(255, 255, 255, 0.14);
                        border-radius: 24px;
                        padding: 28px 24px;
                        box-shadow: 0 24px 60px rgba(0, 0, 0, 0.55);
                        backdrop-filter: blur(18px);
                    }
                    .brand {
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        font-size: 14px;
                        font-weight: 600;
                        color: #b5bac9;
                        margin-bottom: 18px;
                    }
                    .dot {
                        width: 9px;
                        height: 9px;
                        border-radius: 50%;
                        background: #ffffff;
                    }
                    h1 {
                        font-size: 24px;
                        font-weight: 700;
                        letter-spacing: -0.3px;
                        margin-bottom: 8px;
                    }
                    p.sub {
                        font-size: 14px;
                        line-height: 1.5;
                        color: #9ba1b4;
                        margin-bottom: 22px;
                    }
                    label {
                        display: block;
                        font-size: 12px;
                        font-weight: 600;
                        letter-spacing: 0.6px;
                        text-transform: uppercase;
                        color: #b5bac9;
                        margin-bottom: 8px;
                    }
                    input[type="url"], input[type="text"] {
                        width: 100%;
                        background: #0c0e15;
                        border: 1.5px solid rgba(255, 255, 255, 0.18);
                        border-radius: 14px;
                        padding: 15px 16px;
                        font-size: 15px;
                        color: #ffffff;
                        outline: none;
                        transition: border-color 0.2s ease;
                    }
                    input:focus {
                        border-color: #ffffff;
                    }
                    .btn {
                        width: 100%;
                        margin-top: 18px;
                        padding: 15px 20px;
                        border: none;
                        border-radius: 999px;
                        background: #f6f7fb;
                        color: #06070b;
                        font-size: 16px;
                        font-weight: 700;
                        cursor: pointer;
                        transition: opacity 0.18s ease, transform 0.12s ease;
                    }
                    .btn:active {
                        transform: scale(0.98);
                    }
                    .btn:disabled {
                        opacity: 0.6;
                    }
                    .alert {
                        padding: 12px 14px;
                        border-radius: 12px;
                        font-size: 13px;
                        line-height: 1.45;
                        margin-bottom: 18px;
                    }
                    .alert.error {
                        background: rgba(229, 57, 53, 0.18);
                        border: 1px solid rgba(229, 57, 53, 0.45);
                        color: #ff9e99;
                    }
                    .hint {
                        margin-top: 16px;
                        font-size: 12px;
                        color: #7d8396;
                        text-align: center;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="brand"><span class="dot"></span><span>Streamflix TV · Emparejamiento Local</span></div>
                    <h1>Añadir Add-on</h1>
                    <p class="sub">Pega la dirección del archivo <strong>manifest.json</strong> de tu add-on de Stremio para instalarlo directamente en tu televisor.</p>
                    $errorBlock
                    <form id="addonForm" method="POST" action="/install">
                        <label for="manifestUrl">URL del Manifiesto</label>
                        <input
                            id="manifestUrl"
                            name="manifestUrl"
                            type="text"
                            inputmode="url"
                            autocapitalize="off"
                            autocorrect="off"
                            spellcheck="false"
                            required
                            placeholder="https://ejemplo.com/manifest.json"
                            value="$escapedValue"
                        />
                        <button id="submitBtn" class="btn" type="submit">Instalar Add-on</button>
                    </form>
                    <p class="hint">Compatible con enlaces https://, http:// y stremio://</p>
                </div>
                <script>
                    const form = document.getElementById('addonForm');
                    const btn = document.getElementById('submitBtn');
                    form.addEventListener('submit', function() {
                        btn.disabled = true;
                        btn.textContent = 'Validando e instalando...';
                    });
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun buildSuccessHtml(addonName: String): String {
        val safeName = escapeHtml(addonName)
        return """
            <!DOCTYPE html>
            <html lang="es">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Add-on Instalado · Streamflix TV</title>
                <style>
                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                    }
                    body {
                        min-height: 100vh;
                        background: radial-gradient(circle at top, #182620 0%, #06070b 70%);
                        color: #f6f7fb;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 24px;
                    }
                    .card {
                        width: 100%;
                        max-width: 420px;
                        text-align: center;
                        background: rgba(24, 27, 36, 0.9);
                        border: 1px solid rgba(255, 255, 255, 0.15);
                        border-radius: 24px;
                        padding: 34px 24px;
                    }
                    .check {
                        width: 56px;
                        height: 56px;
                        border-radius: 50%;
                        background: #43a047;
                        color: #ffffff;
                        font-size: 28px;
                        line-height: 56px;
                        margin: 0 auto 18px auto;
                    }
                    h1 {
                        font-size: 22px;
                        margin-bottom: 10px;
                    }
                    p {
                        font-size: 14px;
                        color: #b5bac9;
                        line-height: 1.5;
                    }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="check">✓</div>
                    <h1>Add-on instalado correctamente</h1>
                    <p><strong>$safeName</strong> ya está activo en tu Android TV. Puedes cerrar esta pestaña en tu teléfono.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun escapeHtml(input: String): String {
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}
