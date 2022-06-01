# Ouinet's test app

## Prepare your app for using Ouinet

Add Ouinet lib and Relinker to your dependencies list in **app/build.gradle**:

```groovy
dependencies {
    // ...
    implementation 'ie.equalit.ouinet:ouinet-armeabi-v7a:0.20.0'
    implementation 'com.getkeepsafe.relinker:relinker:1.4.4'
}
```

Import `Ouinet` in your Android activity and create a private variable to hold the client:

```kotlin
import ie.equalit.ouinet.Ouinet

class MainActivity : AppCompatActivity() {
    private lateinit var ouinet: Ouinet
    // ...
}
```

Import `Config` and setup the Ouinet client:
```kotlin
import ie.equalit.ouinet.Config
// ...

class MainActivity : AppCompatActivity() {
    // ...

    override fun onCreate(savedInstanceState: Bundle?) {

        var config = Config.ConfigBuilder(this)
            .setCacheType("bep5-http")
            .build()

        ouinet = Ouinet(this, config)
        ouinet.start()
        // ...
    }
}
```

## Pass config values to Ouinet during the build process

You can have Ouinet keys and passwords added to the
client during the building process by Gradle.

You just need to create a `local.properties` file in the root of this project
and set the values as follows before building the app:
```groovy
CACHE_PUB_KEY="YOUR OUINET CACHE PUB KEY"
INJECTOR_CREDENTIALS="ouinet:YOURINJECTORPASSWORD"
// It's important to keep the new line characters in the beggining and the end
// of certificate delimiters
INJECTOR_TLS_CERT="-----BEGIN CERTIFICATE-----\\n\
ABCDEFG...\
\\n-----END CERTIFICATE-----"
```

Those values should be loaded by Gradle during the build process in **app/build.gradle**:
```groovy
...

Properties localProperties = new Properties()
localProperties.load(rootProject.file('local.properties').newDataInputStream())

android {
    compileSdk 32

    defaultConfig {
        ...
        buildConfigField "String", "CACHE_PUB_KEY", localProperties['CACHE_PUB_KEY']
        buildConfigField "String", "INJECTOR_CREDENTIALS", localProperties['INJECTOR_CREDENTIALS']
        buildConfigField "String", "INJECTOR_TLS_CERT", localProperties['INJECTOR_TLS_CERT']
    }
    ...
}
```

and can be referenced after that from Kotlin via `BuildConfig`:

```kotlin
var config = Config.ConfigBuilder(this)
    // ...
    .setCacheHttpPubKey(BuildConfig.CACHE_PUB_KEY)
    .setInjectorCredentials(BuildConfig.INJECTOR_CREDENTIALS)
    .setInjectorTlsCert(BuildConfig.INJECTOR_TLS_CERT)
    .build()
```

## Send an HTTP request through Ouinet

Create a Proxy object pointing to Ouinet's service `127.0.0.1:8077`:
```kotlin
val ouinetService = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 8077))
```

Pass the Proxy object to your HTTP client (we're using `OKHTTPClient` in this example):
```kotlin
OkHttpClient.Builder().proxy(ouinetService).build()
```

## Validate Ouinet's TLS cert
A TLS certificate is automatically generated by Ouinet and used for it's
interactions with the HTTP clients. You can implement a custom `TrustManager`:
```kotlin
inner private class OuinetTrustManager : X509TrustManager {
    // ...

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        //...
    }

    // ...
    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return arrayOf(ca as X509Certificate)
    }
}
```

then you can load the `X509TrustManager`:
```kotlin
ouinetDir = config.ouinetDirectory
caInput = FileInputStream(ouinetDir + "/ssl-ca-cert.pem")
val cf = CertificateFactory.getInstance("X.509")
ca = cf.generateCertificate(caInput)
```

and add it to your own `KeyChain`:

```kotlin
val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
keyStore.load(null, null)
keyStore.setCertificateEntry("ca", certificateAuthority)
```

The resulting `TrustManager` can be used by the `OKHttpClient.Builder` to set
a custom `sslSocketFactory` that verifies only the requests coming from Ouinet:

```kotlin
val builder = OkHttpClient.Builder()
builder.sslSocketFactory(
    getSSLSocketFactory(trustManagers),
    (trustManagers[0] as X509TrustManager)
)
```

## Test Ouinet access mechanisms
During your tests you can easily disable any of the different access methods
available in Ouinet when the Config object is build:

* Force Origin Access
```kotlin
var config = Config.ConfigBuilder(this)
    // ...
    .setDisableProxyAccess(true)
    .setDisableInjectorAccess(true)
    .build()
```

* Force Injector Access
```kotlin
var config = Config.ConfigBuilder(this)
    // ...
    .setDisableOriginAccess(true)
    .setDisableProxyAccess(true)
    .build()
```

* Force Proxy Access
```kotlin
var config = Config.ConfigBuilder(this)
    // ...
    .setDisableOriginAccess(true)
    .setDisableInjectorAccess(true)
    .build()
```