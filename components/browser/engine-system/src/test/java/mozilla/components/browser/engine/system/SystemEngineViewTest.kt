/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.browser.engine.system

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebViewClient
import android.webkit.WebView.HitTestResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import mozilla.components.browser.engine.system.matcher.UrlMatcher
import mozilla.components.browser.errorpages.ErrorType
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.engine.HitResult
import mozilla.components.concept.engine.history.HistoryTrackingDelegate
import mozilla.components.concept.engine.permission.PermissionRequest
import mozilla.components.concept.engine.request.RequestInterceptor
import mozilla.components.support.test.eq
import mozilla.components.support.test.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SystemEngineViewTest {

    @Test
    fun `EngineView initialization`() {
        val engineView = SystemEngineView(RuntimeEnvironment.application)

        assertNotNull(engineView.currentWebView.webChromeClient)
        assertNotNull(engineView.currentWebView.webViewClient)
        assertEquals(engineView.currentWebView, engineView.getChildAt(0))
    }

    @Test
    fun `WebViewClient notifies observers`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observedUrl = ""
        var observedLoadingState = false
        var observedSecurityChange: Triple<Boolean, String?, String?> = Triple(false, null, null)
        engineSession.register(object : EngineSession.Observer {
            override fun onLoadingStateChange(loading: Boolean) { observedLoadingState = loading }
            override fun onLocationChange(url: String) { observedUrl = url }
            override fun onSecurityChange(secure: Boolean, host: String?, issuer: String?) {
                observedSecurityChange = Triple(secure, host, issuer)
            }
        })

        engineView.currentWebView.webViewClient.onPageStarted(null, "https://wiki.mozilla.org/", null)
        assertEquals(true, observedLoadingState)
        assertEquals(observedUrl, "https://wiki.mozilla.org/")

        observedLoadingState = true
        engineView.currentWebView.webViewClient.onPageFinished(null, "http://mozilla.org")
        assertEquals("http://mozilla.org", observedUrl)
        assertFalse(observedLoadingState)
        assertEquals(Triple(false, null, null), observedSecurityChange)

        val view = mock(WebView::class.java)
        engineView.currentWebView.webViewClient.onPageFinished(view, "http://mozilla.org")
        assertEquals(Triple(false, null, null), observedSecurityChange)

        val certificate = mock(SslCertificate::class.java)
        val dName = mock(SslCertificate.DName::class.java)
        doReturn("testCA").`when`(dName).oName
        doReturn(certificate).`when`(view).certificate
        engineView.currentWebView.webViewClient.onPageFinished(view, "http://mozilla.org")

        doReturn("testCA").`when`(dName).oName
        doReturn(dName).`when`(certificate).issuedBy
        doReturn(certificate).`when`(view).certificate
        engineView.currentWebView.webViewClient.onPageFinished(view, "http://mozilla.org")
        assertEquals(Triple(true, "mozilla.org", "testCA"), observedSecurityChange)
    }

    @Test
    fun `HitResult type handling`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        var hitTestResult: HitResult = HitResult.UNKNOWN("")
        engineView.render(engineSession)
        engineSession.register(object : EngineSession.Observer {
            override fun onLongPress(hitResult: HitResult) {
                hitTestResult = hitResult
            }
        })

        engineView.handleLongClick(HitTestResult.EMAIL_TYPE, "mailto:asa@mozilla.com")
        assertTrue(hitTestResult is HitResult.EMAIL)
        assertEquals("mailto:asa@mozilla.com", hitTestResult.src)

        engineView.handleLongClick(HitTestResult.GEO_TYPE, "geo:1,-1")
        assertTrue(hitTestResult is HitResult.GEO)
        assertEquals("geo:1,-1", hitTestResult.src)

        engineView.handleLongClick(HitTestResult.PHONE_TYPE, "tel:+123456789")
        assertTrue(hitTestResult is HitResult.PHONE)
        assertEquals("tel:+123456789", hitTestResult.src)

        engineView.handleLongClick(HitTestResult.IMAGE_TYPE, "image.png")
        assertTrue(hitTestResult is HitResult.IMAGE)
        assertEquals("image.png", hitTestResult.src)

        engineView.handleLongClick(HitTestResult.SRC_ANCHOR_TYPE, "https://mozilla.org")
        assertTrue(hitTestResult is HitResult.UNKNOWN)
        assertEquals("https://mozilla.org", hitTestResult.src)

        var result = engineView.handleLongClick(HitTestResult.SRC_IMAGE_ANCHOR_TYPE, "image.png")
        assertFalse(result) // Intentional for image links; see ImageHandler tests.

        result = engineView.handleLongClick(HitTestResult.EDIT_TEXT_TYPE, "https://mozilla.org")
        assertFalse(result)
    }

    @Test
    fun `ImageHandler notifies observers`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val handler = SystemEngineView.ImageHandler(engineSession)
        val message = mock(Message::class.java)
        val bundle = mock(Bundle::class.java)
        var observerNotified = false

        `when`(message.data).thenReturn(bundle)
        `when`(message.data.getString("url")).thenReturn("https://mozilla.org")
        `when`(message.data.getString("src")).thenReturn("file.png")

        engineView.render(engineSession)
        engineSession.register(object : EngineSession.Observer {
            override fun onLongPress(hitResult: HitResult) {
                observerNotified = true
            }
        })

        handler.handleMessage(message)
        assertTrue(observerNotified)

        observerNotified = false
        val nullHandler = SystemEngineView.ImageHandler(null)
        nullHandler.handleMessage(message)
        assertFalse(observerNotified)
    }

    @Test(expected = IllegalStateException::class)
    fun `null image src`() {
        val engineSession = SystemEngineSession()
        val handler = SystemEngineView.ImageHandler(engineSession)
        val message = mock(Message::class.java)
        val bundle = mock(Bundle::class.java)

        `when`(message.data).thenReturn(bundle)
        `when`(message.data.getString("url")).thenReturn("https://mozilla.org")

        handler.handleMessage(message)
    }

    @Test(expected = IllegalStateException::class)
    fun `null image url`() {
        val engineSession = SystemEngineSession()
        val handler = SystemEngineView.ImageHandler(engineSession)
        val message = mock(Message::class.java)
        val bundle = mock(Bundle::class.java)

        `when`(message.data).thenReturn(bundle)
        `when`(message.data.getString("src")).thenReturn("file.png")

        handler.handleMessage(message)
    }

    @Test
    fun `WebChromeClient notifies observers`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observedProgress = 0
        engineSession.register(object : EngineSession.Observer {
            override fun onProgress(progress: Int) { observedProgress = progress }
        })

        engineView.currentWebView.webChromeClient.onProgressChanged(null, 100)
        assertEquals(100, observedProgress)
    }

    @Test
    fun `SystemEngineView keeps track of current url via onPageStart events`() {
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val webView: WebView = mock()

        assertEquals("", engineView.currentUrl)
        engineView.currentWebView.webViewClient.onPageStarted(webView, "https://www.mozilla.org/", null)
        assertEquals("https://www.mozilla.org/", engineView.currentUrl)

        engineView.currentWebView.webViewClient.onPageStarted(webView, "https://www.firefox.com/", null)
        assertEquals("https://www.firefox.com/", engineView.currentUrl)
    }

    @Test
    fun `WebView client notifies configured history delegate of url visits`() = runBlocking {
        val engineSession = SystemEngineSession()

        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val webView: WebView = mock()
        val historyDelegate: HistoryTrackingDelegate = mock()

        // Nothing breaks if delegate isn't set and session isn't rendered.
        engineView.currentWebView.webViewClient.doUpdateVisitedHistory(webView, "https://www.mozilla.com", false)

        engineView.render(engineSession)

        // Nothing breaks if delegate isn't set.
        engineView.currentWebView.webViewClient.doUpdateVisitedHistory(webView, "https://www.mozilla.com", false)

        engineSession.settings.historyTrackingDelegate = historyDelegate

        engineView.currentWebView.webViewClient.doUpdateVisitedHistory(webView, "https://www.mozilla.com", false)
        verify(historyDelegate).onVisited(eq("https://www.mozilla.com"), eq(false), eq(false))

        engineView.currentWebView.webViewClient.doUpdateVisitedHistory(webView, "https://www.mozilla.com", true)
        verify(historyDelegate).onVisited(eq("https://www.mozilla.com"), eq(true), eq(false))
    }

    @Test
    fun `WebView client requests history from configured history delegate`() {
        val engineSession = SystemEngineSession()

        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val historyDelegate = object : HistoryTrackingDelegate {
            override suspend fun onVisited(uri: String, isReload: Boolean, privateMode: Boolean) {
                fail()
            }

            override suspend fun onTitleChanged(uri: String, title: String, privateMode: Boolean) {
                fail()
            }

            override fun getVisited(uris: List<String>, privateMode: Boolean): Deferred<List<Boolean>> {
                fail()
                return CompletableDeferred(listOf())
            }

            override fun getVisited(privateMode: Boolean): Deferred<List<String>> {
                return CompletableDeferred(listOf("https://www.mozilla.com"))
            }
        }

        // Nothing breaks if delegate isn't set and session isn't rendered.
        engineView.currentWebView.webChromeClient.getVisitedHistory(mock())

        engineView.render(engineSession)

        // Nothing breaks if delegate isn't set.
        engineView.currentWebView.webChromeClient.getVisitedHistory(mock())

        engineSession.settings.historyTrackingDelegate = historyDelegate

        val historyValueCallback: ValueCallback<Array<String>> = mock()
        runBlocking {
            engineView.currentWebView.webChromeClient.getVisitedHistory(historyValueCallback)
        }
        verify(historyValueCallback).onReceiveValue(arrayOf("https://www.mozilla.com"))
    }

    @Test
    fun `WebView client notifies configured history delegate of title changes`() = runBlocking {
        val engineSession = SystemEngineSession()

        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val webView: WebView = mock()
        val historyDelegate: HistoryTrackingDelegate = mock()

        // Nothing breaks if delegate isn't set and session isn't rendered.
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "New title!")

        // This initializes our settings.
        engineView.render(engineSession)

        // Nothing breaks if delegate isn't set.
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "New title!")

        // We can now set the delegate. Were it set before the render call,
        // it'll get overwritten during settings initialization.
        engineSession.settings.historyTrackingDelegate = historyDelegate

        // Delegate not notified if, somehow, there's no currentUrl present in the view.
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "New title!")
        verify(historyDelegate, never()).onTitleChanged(eq(""), eq("New title!"), eq(false))

        // This sets the currentUrl.
        engineView.currentWebView.webViewClient.onPageStarted(webView, "https://www.mozilla.org/", null)

        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "New title!")
        verify(historyDelegate).onTitleChanged(eq("https://www.mozilla.org/"), eq("New title!"), eq(false))

        reset(historyDelegate)

        // Empty title when none provided
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, null)
        verify(historyDelegate).onTitleChanged(eq("https://www.mozilla.org/"), eq(""), eq(false))
    }

    @Test
    fun `WebView client notifies observers about title changes`() {
        val engineSession = SystemEngineSession()

        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val observer: EngineSession.Observer = mock()
        val webView: WebView = mock()
        `when`(webView.canGoBack()).thenReturn(true)
        `when`(webView.canGoForward()).thenReturn(true)

        // This sets the currentUrl.
        engineView.currentWebView.webViewClient.onPageStarted(webView, "https://www.mozilla.org/", null)

        // No observers notified when session isn't rendered.
        engineSession.register(observer)
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "Hello World!")
        verify(observer, never()).onTitleChange("Hello World!")
        verify(observer, never()).onNavigationStateChange(true, true)

        // Observers notified.
        engineView.render(engineSession)

        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, "Hello World!")
        verify(observer).onTitleChange(eq("Hello World!"))
        verify(observer).onNavigationStateChange(true, true)

        reset(observer)

        // Empty title when none provided.
        engineView.currentWebView.webChromeClient.onReceivedTitle(webView, null)
        verify(observer).onTitleChange(eq(""))
        verify(observer).onNavigationStateChange(true, true)
    }

    @Test
    fun `download listener notifies observers`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observerNotified = false

        engineSession.register(object : EngineSession.Observer {
            override fun onExternalResource(
                url: String,
                fileName: String,
                contentLength: Long?,
                contentType: String?,
                cookie: String?,
                userAgent: String?
            ) {
                assertEquals("https://download.mozilla.org", url)
                assertEquals("image.png", fileName)
                assertEquals(1337L, contentLength)
                assertNull(cookie)
                assertEquals("Components/1.0", userAgent)

                observerNotified = true
            }
        })

        val listener = engineView.createDownloadListener()
        listener.onDownloadStart(
            "https://download.mozilla.org",
            "Components/1.0",
            "attachment; filename=\"image.png\"",
            "image/png",
            1337)

        assertTrue(observerNotified)
    }

    @Test
    fun `WebView client tracking protection`() {
        SystemEngineView.URL_MATCHER = UrlMatcher(arrayOf("blocked.random"))

        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        val webViewClient = engineView.currentWebView.webViewClient
        val invalidRequest = mock(WebResourceRequest::class.java)
        `when`(invalidRequest.isForMainFrame).thenReturn(false)
        `when`(invalidRequest.url).thenReturn(Uri.parse("market://foo.bar/"))

        var response = webViewClient.shouldInterceptRequest(engineView.currentWebView, invalidRequest)
        assertNull(response)

        engineSession.trackingProtectionPolicy = EngineSession.TrackingProtectionPolicy.all()
        response = webViewClient.shouldInterceptRequest(engineView.currentWebView, invalidRequest)
        assertNotNull(response)
        assertNull(response.data)
        assertNull(response.encoding)
        assertNull(response.mimeType)

        val faviconRequest = mock(WebResourceRequest::class.java)
        `when`(faviconRequest.isForMainFrame).thenReturn(false)
        `when`(faviconRequest.url).thenReturn(Uri.parse("http://foo/favicon.ico"))
        response = webViewClient.shouldInterceptRequest(engineView.currentWebView, faviconRequest)
        assertNotNull(response)
        assertNull(response.data)
        assertNull(response.encoding)
        assertNull(response.mimeType)

        val blockedRequest = mock(WebResourceRequest::class.java)
        `when`(blockedRequest.isForMainFrame).thenReturn(false)
        `when`(blockedRequest.url).thenReturn(Uri.parse("http://blocked.random"))
        response = webViewClient.shouldInterceptRequest(engineView.currentWebView, blockedRequest)
        assertNotNull(response)
        assertNull(response.data)
        assertNull(response.encoding)
        assertNull(response.mimeType)
    }

    @Test
    @Suppress("Deprecation")
    fun `WebViewClient calls interceptor from deprecated onReceivedError API`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val requestInterceptor: RequestInterceptor = mock()
        val webViewClient = engineView.currentWebView.webViewClient

        // No session or interceptor attached.
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verifyZeroInteractions(requestInterceptor)

        // Session attached, but not interceptor.
        engineView.render(engineSession)
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verifyZeroInteractions(requestInterceptor)

        // Session and interceptor.
        engineSession.settings.requestInterceptor = requestInterceptor
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verify(requestInterceptor).onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random")

        val webView = mock(WebView::class.java)
        engineView.currentWebView = webView
        val errorResponse = RequestInterceptor.ErrorResponse("foo", url = "about:fail")
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verify(webView, never()).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random"))
            .thenReturn(errorResponse)
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verify(webView).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        val errorResponse2 = RequestInterceptor.ErrorResponse("foo")
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verify(webView, never()).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random"))
            .thenReturn(errorResponse2)
        webViewClient.onReceivedError(
            engineView.currentWebView,
            WebViewClient.ERROR_UNKNOWN,
            null,
            "http://failed.random"
        )
        verify(webView).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)
    }

    @Test
    fun `WebViewClient calls interceptor from new onReceivedError API`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val requestInterceptor: RequestInterceptor = mock()
        val webViewClient = engineView.currentWebView.webViewClient
        val webRequest: WebResourceRequest = mock()
        val webError: WebResourceError = mock()
        val url: Uri = mock()

        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verifyZeroInteractions(requestInterceptor)

        engineView.render(engineSession)
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verifyZeroInteractions(requestInterceptor)

        `when`(webError.errorCode).thenReturn(WebViewClient.ERROR_UNKNOWN)
        `when`(webRequest.url).thenReturn(url)
        `when`(url.toString()).thenReturn("http://failed.random")
        engineSession.settings.requestInterceptor = requestInterceptor
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(requestInterceptor, never()).onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random")

        `when`(webRequest.isForMainFrame).thenReturn(true)
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(requestInterceptor).onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random")

        val webView = mock(WebView::class.java)
        engineView.currentWebView = webView
        val errorResponse = RequestInterceptor.ErrorResponse("foo", url = "about:fail")
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(webView, never()).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random"))
            .thenReturn(errorResponse)
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(webView).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        val errorResponse2 = RequestInterceptor.ErrorResponse("foo")
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(webView, never()).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.UNKNOWN, "http://failed.random"))
            .thenReturn(errorResponse2)
        webViewClient.onReceivedError(engineView.currentWebView, webRequest, webError)
        verify(webView).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)
    }

    @Test
    fun `WebViewClient calls interceptor when onReceivedSslError`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val requestInterceptor: RequestInterceptor = mock()
        val webViewClient = engineView.currentWebView.webViewClient
        val handler: SslErrorHandler = mock()
        val error: SslError = mock()

        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verifyZeroInteractions(requestInterceptor)

        engineView.render(engineSession)
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verifyZeroInteractions(requestInterceptor)

        `when`(error.primaryError).thenReturn(SslError.SSL_EXPIRED)
        `when`(error.url).thenReturn("http://failed.random")
        engineSession.settings.requestInterceptor = requestInterceptor
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(requestInterceptor).onErrorRequest(engineSession, ErrorType.ERROR_SECURITY_SSL, "http://failed.random")
        verify(handler, times(3)).cancel()

        val webView = mock(WebView::class.java)
        engineView.currentWebView = webView
        val errorResponse = RequestInterceptor.ErrorResponse("foo", url = "about:fail")
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(webView, never()).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        `when`(
            requestInterceptor.onErrorRequest(
                engineSession,
                ErrorType.ERROR_SECURITY_SSL,
                "http://failed.random"
            )
        ).thenReturn(errorResponse)
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(webView).loadDataWithBaseURL("about:fail", "foo", "text/html", "UTF-8", null)

        val errorResponse2 = RequestInterceptor.ErrorResponse("foo")
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(webView, never()).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.ERROR_SECURITY_SSL, "http://failed.random"))
            .thenReturn(errorResponse2)
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(webView).loadDataWithBaseURL(null, "foo", "text/html", "UTF-8", null)

        `when`(requestInterceptor.onErrorRequest(engineSession, ErrorType.ERROR_SECURITY_SSL, "http://failed.random"))
            .thenReturn(RequestInterceptor.ErrorResponse("foo", "http://failed.random"))
        webViewClient.onReceivedSslError(engineView.currentWebView, handler, error)
        verify(webView).loadDataWithBaseURL("http://failed.random", "foo", "text/html", "UTF-8", null)
    }

    @Test
    fun `WebViewClient blocks WebFonts`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        val webViewClient = engineView.currentWebView.webViewClient
        val webFontRequest = mock(WebResourceRequest::class.java)
        `when`(webFontRequest.url).thenReturn(Uri.parse("/fonts/test.woff"))
        assertNull(webViewClient.shouldInterceptRequest(engineView.currentWebView, webFontRequest))

        engineView.render(engineSession)
        assertNull(webViewClient.shouldInterceptRequest(engineView.currentWebView, webFontRequest))

        engineSession.settings.webFontsEnabled = false

        val request = mock(WebResourceRequest::class.java)
        `when`(request.url).thenReturn(Uri.parse("http://mozilla.org"))
        assertNull(webViewClient.shouldInterceptRequest(engineView.currentWebView, request))

        val response = webViewClient.shouldInterceptRequest(engineView.currentWebView, webFontRequest)
        assertNotNull(response)
        assertNull(response.data)
        assertNull(response.encoding)
        assertNull(response.mimeType)
    }

    @Test
    fun `FindListener notifies observers`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observerNotified = false

        engineSession.register(object : EngineSession.Observer {
            override fun onFindResult(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
                assertEquals(0, activeMatchOrdinal)
                assertEquals(1, numberOfMatches)
                assertTrue(isDoneCounting)
                observerNotified = true
            }
        })

        val listener = engineView.createFindListener()
        listener.onFindResultReceived(0, 1, true)
        assertTrue(observerNotified)
    }

    @Test
    fun `lifecycle methods are invoked`() {
        val webView = mock(WebView::class.java)
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.currentWebView = webView

        engineView.onPause()
        verify(webView).onPause()
        verify(webView).pauseTimers()

        engineView.onResume()
        verify(webView).onResume()
        verify(webView).resumeTimers()

        engineView.onDestroy()
        verify(webView).destroy()
    }

    @Test
    fun `showCustomView notifies fullscreen mode observers and execs callback`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        val observer: EngineSession.Observer = mock()
        engineSession.register(observer)

        val view = mock(View::class.java)
        val customViewCallback = mock(WebChromeClient.CustomViewCallback::class.java)

        engineView.currentWebView.webChromeClient.onShowCustomView(view, customViewCallback)

        verify(observer).onFullScreenChange(true)
    }

    @Test
    fun `addFullScreenView execs callback and removeView`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        val view = View(RuntimeEnvironment.systemContext)
        val customViewCallback = mock(WebChromeClient.CustomViewCallback::class.java)

        assertNull(engineView.fullScreenCallback)

        engineView.currentWebView.webChromeClient.onShowCustomView(view, customViewCallback)

        assertNotNull(engineView.fullScreenCallback)
        assertEquals(customViewCallback, engineView.fullScreenCallback)
        assertEquals("mozac_system_engine_fullscreen", view.tag)

        engineView.currentWebView.webChromeClient.onHideCustomView()
        assertEquals(View.VISIBLE, engineView.currentWebView.visibility)
    }

    @Test
    fun `addFullScreenView with no matching webView`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        val view = View(RuntimeEnvironment.systemContext)
        val customViewCallback = mock(WebChromeClient.CustomViewCallback::class.java)

        engineView.currentWebView.tag = "not_webview"
        engineView.currentWebView.webChromeClient.onShowCustomView(view, customViewCallback)

        assertNotEquals(View.INVISIBLE, engineView.currentWebView.visibility)
    }

    @Test
    fun `removeFullScreenView with no matching views`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        val view = View(RuntimeEnvironment.systemContext)
        val customViewCallback = mock(WebChromeClient.CustomViewCallback::class.java)

        // When the fullscreen view isn't available
        engineView.currentWebView.webChromeClient.onShowCustomView(view, customViewCallback)
        engineView.findViewWithTag<View>("mozac_system_engine_fullscreen").tag = "not_fullscreen"

        engineView.currentWebView.webChromeClient.onHideCustomView()

        assertNotNull(engineView.fullScreenCallback)
        verify(engineView.fullScreenCallback, never())?.onCustomViewHidden()
        assertEquals(View.INVISIBLE, engineView.currentWebView.visibility)

        // When fullscreen view is available, but WebView isn't.
        engineView.findViewWithTag<View>("not_fullscreen").tag = "mozac_system_engine_fullscreen"
        engineView.currentWebView.tag = "not_webView"

        engineView.currentWebView.webChromeClient.onHideCustomView()

        assertEquals(View.INVISIBLE, engineView.currentWebView.visibility)
    }

    @Test
    fun `fullscreenCallback is null`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        engineView.currentWebView.webChromeClient.onHideCustomView()
        assertNull(engineView.fullScreenCallback)
    }

    @Test
    fun `when a page is loaded a thumbnail should be captured`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)
        var thumbnailChanged = false
        engineSession.register(object : EngineSession.Observer {

            override fun onThumbnailChange(bitmap: Bitmap?) {
                thumbnailChanged = bitmap != null
            }
        })

        engineView.currentWebView.webViewClient.onPageFinished(null, "http://mozilla.org")
        assertTrue(thumbnailChanged)
    }

    @Test
    fun `when a page is loaded and the os is in low memory condition none thumbnail should be captured`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        engineView.testLowMemory = true

        var thumbnailChanged = false
        engineSession.register(object : EngineSession.Observer {

            override fun onThumbnailChange(bitmap: Bitmap?) {
                thumbnailChanged = bitmap != null
            }
        })
        engineView.currentWebView.webViewClient.onPageFinished(null, "http://mozilla.org")
        assertFalse(thumbnailChanged)
    }

    @Test
    fun `onPageFinished handles invalid URL`() {
        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observedUrl = ""
        var observedLoadingState = true
        var observedSecurityChange: Triple<Boolean, String?, String?> = Triple(false, null, null)
        engineSession.register(object : EngineSession.Observer {
            override fun onLoadingStateChange(loading: Boolean) { observedLoadingState = loading }
            override fun onLocationChange(url: String) { observedUrl = url }
            override fun onSecurityChange(secure: Boolean, host: String?, issuer: String?) {
                observedSecurityChange = Triple(secure, host, issuer)
            }
        })

        // We need a certificate to trigger parsing the potentially invalid URL for
        // the host parameter in onSecurityChange
        val view = mock(WebView::class.java)
        val certificate = mock(SslCertificate::class.java)
        val dName = mock(SslCertificate.DName::class.java)
        doReturn("testCA").`when`(dName).oName
        doReturn(dName).`when`(certificate).issuedBy
        doReturn(certificate).`when`(view).certificate

        engineView.currentWebView.webViewClient.onPageFinished(view, "invalid:")
        assertEquals("invalid:", observedUrl)
        assertFalse(observedLoadingState)
        assertEquals(Triple(true, null, "testCA"), observedSecurityChange)
    }

    @Test
    fun `URL matcher categories can be changed`() {
        SystemEngineView.URL_MATCHER = null

        var urlMatcher = SystemEngineView.getOrCreateUrlMatcher(RuntimeEnvironment.application,
                TrackingProtectionPolicy.select(TrackingProtectionPolicy.AD, TrackingProtectionPolicy.ANALYTICS)
        )
        assertEquals(setOf(UrlMatcher.ADVERTISING, UrlMatcher.ANALYTICS), urlMatcher.enabledCategories)

        urlMatcher = SystemEngineView.getOrCreateUrlMatcher(RuntimeEnvironment.application,
                TrackingProtectionPolicy.select(TrackingProtectionPolicy.AD, TrackingProtectionPolicy.SOCIAL)
        )
        assertEquals(setOf(UrlMatcher.ADVERTISING, UrlMatcher.SOCIAL), urlMatcher.enabledCategories)
    }

    @Test
    fun `permission requests are forwarded to observers`() {
        val permissionRequest: android.webkit.PermissionRequest = mock()
        `when`(permissionRequest.resources).thenReturn(emptyArray())
        `when`(permissionRequest.origin).thenReturn(Uri.parse("https://mozilla.org"))

        val engineSession = SystemEngineSession()
        val engineView = SystemEngineView(RuntimeEnvironment.application)
        engineView.render(engineSession)

        var observedPermissionRequest: PermissionRequest? = null
        var cancelledPermissionRequest: PermissionRequest? = null
        engineSession.register(object : EngineSession.Observer {
            override fun onContentPermissionRequest(permissionRequest: PermissionRequest) {
                observedPermissionRequest = permissionRequest
            }

            override fun onCancelContentPermissionRequest(permissionRequest: PermissionRequest) {
                cancelledPermissionRequest = permissionRequest
            }
        })

        engineView.currentWebView.webChromeClient.onPermissionRequest(permissionRequest)
        assertNotNull(observedPermissionRequest)

        engineView.currentWebView.webChromeClient.onPermissionRequestCanceled(permissionRequest)
        assertNotNull(cancelledPermissionRequest)
    }
}