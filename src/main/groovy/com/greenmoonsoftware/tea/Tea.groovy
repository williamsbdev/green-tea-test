package com.greenmoonsoftware.tea

import groovy.json.JsonBuilder
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovyx.net.http.ContentEncoding
import groovyx.net.http.HttpResponseException
import groovyx.net.http.RESTClient

class Tea {
    def host
    def params
    private def action
    private List asserts = []
    private Closure verifyResponseClosure
    private Closure verifyHeadersClosure
    private Closure configureClientClosure
    private Map headers = [:]
    private log = false
    private brewed = false
    private def customParsers = [:].withDefault { return { } }
    private List<Closure> recorders = []
    private boolean gzip
    private proxy = [:]

    def Tea(String host, Map params = [:]) {
        this.host = host
        this.params = params
    }

    def configureClient(RESTClient rest) {
        if (gzip) { rest.contentEncoding = ContentEncoding.Type.GZIP }
        if (proxy) { rest.setProxy(proxy.host, proxy.port, proxy.scheme) }
        def restParams = rest.client.getParams()
        this.params.each { key, value -> restParams.setParameter(key, value) }
        rest.client.setParams(restParams)
        if (configureClientClosure) {
            configureClientClosure(rest)
        }
    }

    def brew() {
        rejectIfReused()
        gatherHostAndUri()

        RESTClient rest = new GreenTeaRestClient(this.host)
        configureClient(rest)
        registerCustomParsers(rest)

        applyHeaders(rest)
        def response = executeHttp(rest)
        record(rest, response)

        evaluateAsserts(response)
        evaluateHeaders(response)
        evaluateResponse(response)
        new Result(condition: (asserts.size() == 0)?Result.Condition.WARN : Result.Condition.SUCCESS)
    }

    def record(GreenTeaRestClient rest, response) {
        if (recorders) {
            def request = rest.client.request
            def reqHeaders = [:]
            request.allHeaders.each { reqHeaders[it.name] = it.value}
            def respHeaders = [:]
            response.headers.each { respHeaders[it.name] = it.value}
            def data = new HttpMetaData(
                host: host
                , uri: action.params.path
                , method: action.method.toUpperCase()
                , responseStatus: response.status
                , requestHeaders: reqHeaders
                , queryParameters: action.params.query
                , requestBody: action.params.body
                , responseHeaders: respHeaders
                , responseBody: response.data
            )
            recorders.each {
                it(data)
            }
        }
    }

    private evaluateResponse(response) {
        if (verifyResponseClosure) {
            verifyResponseClosure(response.data)
        }
    }

    private evaluateHeaders(response) {
        if (verifyHeadersClosure) {
            verifyHeadersClosure(response.headers)
        }
    }

    private evaluateAsserts(response) {
        asserts.each { a ->
            a.eval(response)
        }
    }

    private printLog(HttpMetaData metaData) {
        println "Request URL: ${metaData.host}${metaData.uri}"
        println "Request Method: ${metaData.method.toUpperCase()}"
        println "Status Code: ${metaData.responseStatus}"
        println "Request Headers"
        metaData.requestHeaders.each { k, v -> println "\t${k}: ${v}" }
        if (action.params.query) {
            println "Request Query Params"
            println "\t" + new JsonBuilder(action.params.query)
        }
        if (action.params.body) {
            println "Request Body"
            println "\t" + new JsonBuilder(action.params.body)
        }

        println "Response Headers"
        metaData.responseHeaders.each { k,v -> println "\t${k}: ${v}" }

        def responseContentType = JsonRecorder.extractContentType(metaData.responseHeaders)
        def responseBody = JsonRecorder.encodeBody(responseContentType, metaData.responseBody)
        try {
            println JsonOutput.prettyPrint(responseBody)
        }
        catch (JsonException e) {
            println responseBody
        }
    }

    private executeHttp(RESTClient rest) {
        def response
        try {
            response = rest."${action.method}"(action.params.clone())
            //copy map since RESTClient messes with the provided map
        }
        catch (HttpResponseException ex) {
            response = ex.response
        }
        response
    }

    private applyHeaders(rest) {
        headers.each { k, v ->
            rest.headers."${k}" = v
        }
    }

    private registerCustomParsers(rest) {
        customParsers.each { k, v ->
            rest.parser."${k}" = v(rest)
        }
    }

    private gatherHostAndUri() {
        def (host, uri) = parseForHost(action.params.path)
        if (host != null) {
            this.host = host
        }
        action.params.path = uri
    }

    private rejectIfReused() {
        if (brewed) {
            throw new RuntimeException("This tea is old. You cannot brew the same instance more than once.")
        }
        brewed = true
    }

    private def parseForHost(String url) {
        def host = null
        def uri = url
        if (url.indexOf('http') == 0) {
            def protocalPlus = url.split('://')
            def protocol = protocalPlus[0]
            def hostname = protocalPlus[1].substring(0, protocalPlus[1].indexOf('/'))
            host = protocol+"://"+hostname
            uri = protocalPlus[1].substring(protocalPlus[1].indexOf('/'))
        }
        return [host,uri]
    }

    def Tea get(String url, Map query = null, String requestContentType = 'application/json') {
        if(url.contains("?")) {
            throw new IllegalArgumentException('URL cannot have query params. Please pass as a map as the second param to \'get\'.')
        }
        action = [method:"get", params:[path:url, query:query, requestContentType: requestContentType]]
        return this;
    }

    def Tea post(String url, Object json = null, String requestContentType = 'application/json'){
        action = [method:"post", params:[path:url, body:json, requestContentType: requestContentType]]
        return this
    }

    def Tea put(String url, Object json = null, String requestContentType = 'application/json'){
        action = [method:"put", params:[path:url, body:json, requestContentType: requestContentType]]
        return this
    }

    def Tea patch(String url, Object json = null, String requestContentType = 'application/json') {
        action = [method:"patch", params:[path:url, body:json, requestContentType: requestContentType]]
        return this
    }

    def Tea delete(String url, Map query = null) {
        action = [method:"delete", params:[path:url, query:query]]
        return this
    }

    def expectStatus(int code) {
        asserts.add([eval: { response ->
            assert response.status == code
        }])
        return this
    }

    def verifyResponse(Closure c) {
        verifyResponseClosure = c
        return this
    }

    def verifyHeaders(Closure c) {
        verifyHeadersClosure = c
        return this
    }

    def userAgent(String ua) {
        addHeader("User-Agent", ua)
        return this
    }

    def addHeader(String header, String value) {
        headers[header] = value
        return this
    }

    def basicAuth(String username, String password) {
        def auth = "Basic " + "${username}:${password}".getBytes().encodeBase64().toString()
        addHeader("Authorization", auth)
        return this
    }

    def log() {
        recorders << this.&printLog
        return this
    }

    def withParser(String contentType, Closure createParser) {
        customParsers[contentType] = createParser
        return this
    }

    def withRecorder(Closure r) {
        recorders << r
        return this
    }

    def gzip() {
        addHeader('Accept-Encoding', 'gzip')
        gzip = true
        return this
    }

    def proxy(String host, int port, String scheme = 'http') {
        this.proxy = [host: host, port: port, scheme: scheme]
        return this
    }
    
    def configureClient(Closure c) {
        configureClientClosure = c
        return this
    }
}
