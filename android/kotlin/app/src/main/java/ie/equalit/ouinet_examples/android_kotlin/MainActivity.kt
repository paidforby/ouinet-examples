package ie.equalit.ouinet_examples.android_kotlin

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ie.equalit.ouinet.*
import okhttp3.*
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import javax.net.ssl.*

class MainActivity : AppCompatActivity() {
    lateinit var ouinetDir: String
    private lateinit var ouinetBackground : OuinetBackground
    private val TAG = "OuinetTester"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val get = findViewById<Button>(R.id.get)
        get.setOnClickListener{ getURL(get) }

        val ouinetConfig = Config.ConfigBuilder(this)
            .setCacheType("bep5-http")
            .setTlsCaCertStorePath("file:///android_asset/cacert.pem")
            .setCacheHttpPubKey(BuildConfig.CACHE_PUB_KEY)
            .setInjectorCredentials(BuildConfig.INJECTOR_CREDENTIALS)
            .setInjectorTlsCert(BuildConfig.INJECTOR_TLS_CERT)
            .build()
        ouinetDir = ouinetConfig.ouinetDirectory

        val notificationConfig = NotificationConfig.Builder(this)
            .setHomeActivity("$packageName.$localClassName")
            .setNotificationIcons(
                statusIcon = R.drawable.ic_launcher_foreground,
                //homeIcon = R.drawable.ic_globe_pm,
                //clearIcon = R.drawable.ic_cancel_pm
            )
            /*
            .disableStatusUpdate(true)
            .setChannelName(getString(R.string.notification_channel_name)
            .setNotificationText(
                title = getString(R.string.notification_title),
                description = getString(R.string.notification_description),
                homeText = getString(R.string.notification_home_description),
                clearText = getString(R.string.notification_clear_description),
                confirmText = getString(R.string.notification_clear_do_description),
            )
            .setUpdateInterval(3000)
            */
            .build()

        ouinetBackground = OuinetBackground.Builder(this)
            .setOuinetConfig(ouinetConfig)
            .setNotificationConfig(notificationConfig)
            //.setOnNotificationTappedListener { onTapped() }
            //.setOnConfirmTappedListener { onClear() }
            .build()

        ouinetBackground.startup()

        val restart = findViewById<Button>(R.id.restart)
        restart.setOnClickListener{ restartOuinet() }

        Executors.newFixedThreadPool(1).execute(Runnable { updateOuinetState() })
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if(intent?.hasExtra(OuinetNotification.FROM_NOTIFICATION_EXTRA) == true){
           Log.i(TAG, "$localClassName started from notification")
        }
    }

    private fun onTapped() {
        Log.d(TAG, "Trying onTapped")
        ouinetBackground.shutdown(false)
    }

    private fun onClear() {
        Log.d(TAG, "Trying onClose")
        ouinetBackground.shutdown(true)
    }

    private fun updateOuinetState() {
        val ouinetState = findViewById<View>(R.id.status) as TextView
        while (true) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
            val state = ouinetBackground.getState()
            runOnUiThread { ouinetState.text = getString(R.string.state, state) }
        }
    }

    private fun restartOuinet(){
        /* Restarted ouinet inside of a thread to prevent UI hanging,
         * because the stop method is a blocking function */
        Thread(Runnable {
            ouinetBackground.stop()
            ouinetBackground.start()
        }).start()
    }

    fun getURL(view: View?) {
        val editText = findViewById<View>(R.id.url) as EditText
        val logViewer = findViewById<View>(R.id.log_viewer) as TextView
        val url = editText.text.toString()
        val toast = Toast.makeText(this, "Loading: $url", Toast.LENGTH_SHORT)
        toast.show()

        val client: OkHttpClient = getOuinetHttpClient()
        val request: Request = Request.Builder()
            .url(url)
            .header("X-Ouinet-Group", getDhtGroup(url))
            .build()


        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                runOnUiThread { logViewer.text = e.toString() }
            }

            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) {
                response.body.use { _ ->
                    val responseHeaders = response.headers
                    var i = 0
                    val size = responseHeaders.size
                    while (i < size) {
                        println(responseHeaders.name(i) + ": " + responseHeaders.value(i))
                        i++
                    }
                    runOnUiThread { logViewer.text = responseHeaders.toString() }
                }
            }
        })
    }

    private fun getDhtGroup(url: String): String {
        var domain: String = ""
        try {
            domain = URI(url).schemeSpecificPart
            domain = domain.replace("^//".toRegex(), "")
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }
        return domain
    }

    private fun getOuinetHttpClient(): OkHttpClient {
        try {
            val trustManagers: Array<TrustManager> = getOuinetTrustManager()

            val builder = OkHttpClient.Builder()
            builder.sslSocketFactory(
                getSSLSocketFactory(trustManagers),
                (trustManagers[0] as X509TrustManager)
            )

            // Proxy to ouinet service
            val ouinetService = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8077))
            builder.proxy(ouinetService)
            return builder.build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    @Throws(NoSuchAlgorithmException::class, KeyManagementException::class)
    private fun getSSLSocketFactory(trustManagers: Array<TrustManager>): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagers, SecureRandom())
        return sslContext.socketFactory
    }

    @Throws(
        NoSuchAlgorithmException::class,
        KeyStoreException::class,
        CertificateException::class,
        IOException::class
    )
    private fun getOuinetTrustManager(): Array<TrustManager> {
        return arrayOf(OuinetTrustManager())
    }

    inner private class OuinetTrustManager : X509TrustManager {
        private var trustManager: X509TrustManager? = null
        private var ca: Certificate? = null

        init {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(keyStore)
            for (tm in tmf.trustManagers) {
                if (tm is X509TrustManager) {
                    trustManager = tm
                    break
                }
            }
        }

        @get:Throws(
            KeyStoreException::class,
            CertificateException::class,
            NoSuchAlgorithmException::class,
            IOException::class
        )
        private val keyStore: KeyStore
            get() {
                val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
                keyStore.load(null, null)
                keyStore.setCertificateEntry("ca", certificateAuthority)
                return keyStore
            }

        @get:Throws(CertificateException::class)
        private val certificateAuthority: Certificate?
            get() {
                var caInput: InputStream? = null
                try {
                    caInput = FileInputStream(ouinetDir + "/ssl-ca-cert.pem")
                } catch (e: FileNotFoundException) {
                    e.printStackTrace()
                }
                val cf = CertificateFactory.getInstance("X.509")
                ca = cf.generateCertificate(caInput)
                return ca
            }

        @Throws(CertificateException::class)
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @Throws(CertificateException::class)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            for (cert in chain) {
                Log.d(TAG, "Server Cert Issuer: " + cert.issuerDN.name + " " + cert.subjectDN.name)
            }
            for (cert in trustManager!!.acceptedIssuers) {
                Log.d(TAG, "Client Trusted Issuer: " + cert.issuerDN.name)
            }
            trustManager!!.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return arrayOf(ca as X509Certificate)
        }
    }
}