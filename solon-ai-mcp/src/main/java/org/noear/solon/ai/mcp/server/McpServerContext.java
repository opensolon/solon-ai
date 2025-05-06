package org.noear.solon.ai.mcp.server;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.WebRxSseServerTransportProvider;
import org.noear.solon.core.handle.*;
import org.noear.solon.core.util.MimeType;
import org.noear.solon.core.util.MultiMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collection;
import java.util.zip.GZIPOutputStream;

/**
 * Mcp 服务端请求上下文
 *
 * @author noear
 * @since 3.2
 */
public class McpServerContext extends Context {
    /// /////////////////////////
    private final McpSyncServerExchange serverExchange;
    private final Context context;

    public McpServerContext(McpSyncServerExchange serverExchange) {
        this.serverExchange = serverExchange;
        this.context = ((WebRxSseServerTransportProvider.WebRxMcpSessionTransport) serverExchange.getSession().getTransport()).getContext();
    }

    /**
     * 获取会话 id
     */
    @Override
    public String sessionId() {
        return serverExchange.getSession().getId();
    }

    /// ////////////////

    @Override
    public boolean isHeadersSent() {
        return context.isHeadersSent();
    }

    @Override
    public Object request() {
        return context.request();
    }

    @Override
    public String remoteIp() {
        return context.remoteIp();
    }

    @Override
    public int remotePort() {
        return context.remotePort();
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String protocol() {
        return context.protocol();
    }

    @Override
    public URI uri() {
        return context.uri();
    }

    @Override
    public String path() {
        return context.path();
    }

    @Override
    public boolean isSecure() {
        return context.isSecure();
    }

    @Override
    public String url() {
        return context.url();
    }

    @Override
    public long contentLength() {
        return 0L;
    }

    @Override
    public String contentType() {
        return MimeType.APPLICATION_JSON_VALUE;
    }

    @Override
    public String contentCharset() {
        return context.contentCharset();
    }

    @Override
    public String queryString() {
        return context.queryString();
    }

    @Override
    public InputStream bodyAsStream() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap<String> paramMap() {
        return context.paramMap();
    }

    @Override
    public void filesDelete() throws IOException {

    }

    @Override
    public MultiMap<UploadedFile> fileMap() {
        throw new UnsupportedOperationException();
    }

    /// ////////////////////////////

    @Override
    public String cookie(String name) {
        return context.cookie(name);
    }

    @Override
    public String cookieOrDefault(String name, String def) {
        return context.cookieOrDefault(name, def);
    }

    @Override
    public String[] cookieValues(String name) {
        return context.cookieValues(name);
    }

    @Override
    public Collection<String> cookieNames() {
        return context.cookieNames();
    }

    @Override
    public MultiMap<String> cookieMap() {
        throw new UnsupportedOperationException();
    }

    /// ////////////////////////////

    @Override
    public String header(String name) {
        return context.header(name);
    }

    @Override
    public String headerOrDefault(String name, String def) {
        return context.headerOrDefault(name, def);
    }

    @Override
    public String[] headerValues(String name) {
        return context.headerValues(name);
    }

    @Override
    public Collection<String> headerNames() {
        return context.headerNames();
    }

    @Override
    public MultiMap<String> headerMap() {
        throw new UnsupportedOperationException();
    }


    /// ////////////////////////////


    @Override
    public SessionState sessionState() {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T session(String name, Class<T> clz) {
        return context.session(name, clz);
    }

    @Override
    public double sessionAsDouble(String name) {
        return context.sessionAsDouble(name);
    }

    @Override
    public double sessionAsDouble(String name, double def) {
        return context.sessionAsDouble(name, def);
    }

    @Override
    public int sessionAsInt(String name) {
        return context.sessionAsInt(name);
    }

    @Override
    public int sessionAsInt(String name, int def) {
        return context.sessionAsInt(name, def);
    }

    @Override
    public long sessionAsLong(String name) {
        return context.sessionAsLong(name);
    }

    @Override
    public long sessionAsLong(String name, long def) {
        return context.sessionAsLong(name, def);
    }

    @Override
    public <T> T sessionOrDefault(String name, T def) {
        return context.sessionOrDefault(name, def);
    }

    @Override
    public void sessionSet(String name, Object val) {

    }

    @Override
    public void sessionClear() {

    }

    @Override
    public void sessionRemove(String name) {

    }

    @Override
    public void sessionReset() {

    }

    /// /////////////////

    @Override
    public Object response() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void contentTypeDoSet(String contentType) {

    }

    @Override
    public void output(byte[] bytes) {

    }

    @Override
    public void output(InputStream stream) {

    }

    @Override
    public OutputStream outputStream() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public GZIPOutputStream outputStreamAsGzip() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void outputAsFile(DownloadedFile file) throws IOException {

    }

    @Override
    public void outputAsFile(File file) throws IOException {

    }

    @Override
    public void headerSet(String name, String val) {

    }

    @Override
    public void headerAdd(String name, String val) {

    }

    @Override
    public String headerOfResponse(String name) {
        return context.headerOfResponse(name);
    }

    @Override
    public Collection<String> headerValuesOfResponse(String name) {
        return context.headerValuesOfResponse(name);
    }

    @Override
    public Collection<String> headerNamesOfResponse() {
        return context.headerNamesOfResponse();
    }

    @Override
    public void cookieSet(Cookie cookie) {

    }

    @Override
    public void redirect(String url, int code) {

    }

    @Override
    public int status() {
        return 200;
    }

    @Override
    protected void statusDoSet(int status) {

    }

    @Override
    public void flush() throws IOException {

    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public boolean asyncSupported() {
        return false;
    }

    @Override
    public void asyncListener(ContextAsyncListener listener) {

    }

    @Override
    public void asyncStart(long timeout, Runnable runnable) {

    }

    @Override
    public boolean asyncStarted() {
        return false;
    }

    @Override
    public void asyncComplete() {

    }
}