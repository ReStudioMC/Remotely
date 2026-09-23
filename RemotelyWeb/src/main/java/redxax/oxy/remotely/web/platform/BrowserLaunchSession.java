package redxax.oxy.remotely.web.platform;

import org.teavm.jso.JSBody;
import restudio.rescreen.platform.Async;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BrowserLaunchSession {
    private static final HashMap<Integer, Callback> CALLBACKS = new HashMap<>();
    private static final HashMap<Integer, Callback> PRIMARY_CALLBACKS = new HashMap<>();
    private static final HashMap<Integer, Async<Metadata>> PRIMARY_RESULTS = new HashMap<>();
    private static final HashMap<Integer, Long> REQUEST_GENERATIONS = new HashMap<>();
    private static final List<Runnable> AUTH_STATE_LISTENERS = new ArrayList<>();
    private static final List<Runnable> TICKET_LISTENERS = new ArrayList<>();
    private static final List<Runnable> SESSION_EXPIRY_LISTENERS = new ArrayList<>();
    private static int nextRequestId = 1;
    private static long requestGeneration = 1;
    private static Metadata activeSession;
    private static long authorityGeneration;
    private static String authorityKey = "";
    private static String demoLeaseExpiresAt = "";
    private static Set<String> demoEditablePaths = Set.of();
    private static boolean renewalInFlight;
    private static final List<Async<Metadata>> RENEWAL_WAITERS = new ArrayList<>();
    private static Callback interactiveLoginCallback;
    private static int interactiveLoginAttempts;
    private static int interactiveLoginRequestId;

    private static final int MAX_INTERACTIVE_LOGIN_ATTEMPTS = 240;

    private BrowserLaunchSession() {
    }

    public static void launch(Callback callback) {
        launch(false, callback);
    }

    public static void launch(boolean forceLogin, Callback callback) {
        if (callback == null) {
            return;
        }
        failPendingOperations(new IllegalStateException("Browser Session Superseded"));
        advanceRequestGeneration();
        clearCallbacks();
        boolean wasAuthenticated = authenticated();
        installSession(null);
        demoLeaseExpiresAt = "";
        demoEditablePaths = Set.of();
        renewalInFlight = false;
        finishRenewalWaiters(null, new IllegalStateException("Browser Session Superseded"));
        cancelRenewal();
        cancelDemoLeaseExpiry();
        if (wasAuthenticated) {
            notifyTicketChanged();
            notifyAuthStateChanged();
        }
        String origin = backendOrigin();
        if (origin == null || origin.isBlank()) {
            callback.failed("Remotely Web Backend Is Not Configured");
            return;
        }
        if (localUnauthenticatedPreview()) {
            callback.ready(unauthenticatedMetadata());
            return;
        }
        int requestId = nextId();
        registerCallback(requestId, callback);
        PRIMARY_CALLBACKS.put(requestId, callback);
        requestLaunch(requestId, forceLogin);
    }

    public static void complete(int requestId, String grantId, String ticket, String audience, String scopes, String assignedNode, String expiresAt) {
        complete(requestId, grantId, ticket, audience, scopes, assignedNode, expiresAt, "", "", "", "", "", "");
    }

    public static void complete(int requestId, String grantId, String ticket, String audience, String scopes, String assignedNode, String expiresAt,
                                String subjectId, String username, String displayName, String email, String avatarUrl, String sessionLabel) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        Callback callback = CALLBACKS.get(requestId);
        if (callback != null) {
            if (ticket == null || ticket.isBlank()) {
                removeCallback(requestId);
                callback.failed("Browser ReSync Ticket Was Not Issued");
                return;
            }
            Set<String> scopeSet = new HashSet<>();
            if (scopes != null && !scopes.isBlank()) {
                int start = 0;
                while (start <= scopes.length()) {
                    int end = scopes.indexOf(',', start);
                    if (end < 0) {
                        end = scopes.length();
                    }
                    String scope = scopes.substring(start, end);
                    if (!scope.isBlank()) {
                        scopeSet.add(scope);
                    }
                    if (end == scopes.length()) {
                        break;
                    }
                    start = end + 1;
                }
            }
            boolean authenticated = authenticated();
            String previousTicket = ticket();
            Metadata previous = metadata();
            String resolvedSubjectId = firstNonBlank(subjectId, previous.subjectId());
            boolean accountChanged = subjectId != null && !subjectId.isBlank() && !previous.subjectId().isBlank()
                    && !subjectId.equals(previous.subjectId());
            String resolvedUsername = resolveAccountValue(username, previous.username(), accountChanged);
            String resolvedDisplayName = resolveAccountValue(displayName, previous.displayName(), accountChanged);
            String resolvedEmail = resolveAccountValue(email, previous.email(), accountChanged);
            String resolvedAvatarUrl = resolveAccountValue(avatarUrl, previous.avatarUrl(), accountChanged);
            String resolvedSessionLabel = resolveAccountValue(sessionLabel, previous.sessionLabel(), accountChanged);
            installSession(new Metadata(grantId, ticket, audience, Set.copyOf(scopeSet), assignedNode, expiresAt,
                    resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel));
            persistAccountMetadata(resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel);
            removeCallback(requestId);
            scheduleRenewal(expiresAt);
            if (!ticket.equals(previousTicket)) {
                notifyTicketChanged();
            }
            if (!authenticated || accountChanged || !previous.sameAuthority(activeSession)) {
                notifyAuthStateChanged();
            }
            callback.ready(activeSession);
        }
    }

    public static void fail(int requestId, String message) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        Callback callback = removeCallback(requestId);
        if (callback != null) {
            callback.failed(message == null || message.isBlank() ? "Remotely web launch failed" : message);
        }
    }

    public static void unauthenticated(int requestId) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        boolean renewal = PRIMARY_RESULTS.containsKey(requestId) && activeSession != null;
        if (renewal) notifySessionExpiryListeners();
        Callback callback = removeCallback(requestId);
        if (callback == null) {
            return;
        }
        boolean changed = activeSession != null;
        installSession(null);
        if (changed) {
            notifyAuthStateChanged();
        }
        callback.ready(unauthenticatedMetadata());
    }

    public static boolean beginInteractiveLogin(boolean forceLogin, Callback callback) {
        if (callback == null) {
            return false;
        }
        failPendingOperations(new IllegalStateException("Browser Session Superseded"));
        advanceRequestGeneration();
        clearCallbacks();
        renewalInFlight = false;
        finishRenewalWaiters(null, new IllegalStateException("Browser Session Superseded"));
        cancelRenewal();
        cancelDemoLeaseExpiry();
        String url = loginUrl(forceLogin);
        if (url == null || url.isBlank() || !openLoginWindow(url)) {
            return false;
        }
        interactiveLoginCallback = callback;
        interactiveLoginAttempts = 0;
        scheduleInteractiveLoginPoll(250);
        return true;
    }

    public static void signOut() {
        boolean demo = metadata().demo();
        clearLocalSession();
        clearAccountMetadata();
        if (demo) requestDemoEnd();
        else requestLogout();
    }

    public static boolean authenticated() {
        Metadata session = activeSession;
        return session != null && session.ticket() != null && !session.ticket().isBlank();
    }

    public static Metadata metadata() {
        Metadata session = activeSession;
        return session == null ? durableMetadata() : session;
    }

    public static String authorityKey() {
        return authorityKey;
    }

    private static void installSession(Metadata session) {
        if (session == null) {
            authorityKey = "";
        } else if (activeSession == null || !activeSession.sameAuthority(session)) {
            authorityKey = "browser-session-" + ++authorityGeneration;
        }
        activeSession = session;
    }

    public static String ticket() {
        Metadata session = activeSession;
        return session == null || session.ticket() == null ? "" : session.ticket();
    }

    public static void demoLeaseExpires(String value) {
        demoLeaseExpiresAt = value == null ? "" : value.strip();
    }

    public static void startDemoLeaseExpiry(String value) {
        demoLeaseExpires(value);
        scheduleDemoLeaseExpiry(demoLeaseExpiresAt);
    }

    public static String demoLeaseExpiresAt() {
        return demoLeaseExpiresAt;
    }

    public static void demoEditablePaths(String value) {
        if (value == null || value.isBlank()) {
            demoEditablePaths = Set.of();
            return;
        }
        Set<String> paths = new HashSet<>();
        for (String candidate : value.split("\\n")) {
            String path = normalizedDemoPath(candidate);
            if (!path.isBlank()) paths.add(path);
        }
        demoEditablePaths = Set.copyOf(paths);
    }

    public static boolean demoPathEditable(String value) {
        return demoPathEditable(value, metadata().demo());
    }

    static boolean demoPathEditable(String value, boolean demo) {
        return demo && demoEditablePaths.contains(normalizedDemoPath(value));
    }

    public static void demoLeaseExpired() {
        if (metadata().demo()) expireSession();
    }

    public static void renew() {
        renewAsync();
    }

    public static Async<Metadata> renewAsync() {
        if (!authenticated()) return Async.failed(new IllegalStateException("Browser Session Expired"));
        if (renewalInFlight) {
            Async<Metadata> waiting = Async.pending();
            RENEWAL_WAITERS.add(waiting);
            return waiting;
        }
        renewalInFlight = true;
        Async<Metadata> result = Async.pending();
        int requestId = nextId();
        PRIMARY_RESULTS.put(requestId, result);
        registerCallback(requestId, new Callback() {
            @Override
            public void ready(Metadata metadata) {
                renewalInFlight = false;
                PRIMARY_RESULTS.remove(requestId);
                if (metadata == null || metadata.ticket().isBlank()) {
                    IllegalStateException failure = new IllegalStateException("Browser Session Expired");
                    result.fail(failure);
                    notifySessionExpiryListeners();
                    finishRenewalWaiters(null, failure);
                    clearLocalSession();
                    return;
                }
                result.complete(metadata);
                finishRenewalWaiters(metadata, null);
            }

            @Override
            public void failed(String message) {
                renewalInFlight = false;
                PRIMARY_RESULTS.remove(requestId);
                IllegalStateException failure = new IllegalStateException(message == null || message.isBlank() ? "Browser Session Renewal Failed" : message);
                result.fail(failure);
                finishRenewalWaiters(null, failure);
                if (activeSession != null) scheduleRenewalRetry();
            }
        });
        requestLaunch(requestId, false);
        return result;
    }

    public static boolean isAuthenticationFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if ("Browser Session Expired".equals(current.getMessage())) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void finishRenewalWaiters(Metadata metadata, Throwable failure) {
        if (RENEWAL_WAITERS.isEmpty()) return;
        List<Async<Metadata>> waiters = new ArrayList<>(RENEWAL_WAITERS);
        RENEWAL_WAITERS.clear();
        for (Async<Metadata> waiter : waiters) {
            if (failure == null) waiter.complete(metadata);
            else waiter.fail(failure);
        }
    }

    public static Metadata unauthenticatedMetadata() {
        return new Metadata("", "", "", Set.of(), "", "");
    }

    public static void updateAccountMetadata(String subjectId, String username, String displayName, String email,
                                             String avatarUrl, String sessionLabel) {
        Metadata previous = metadata();
        String resolvedSubjectId = firstNonBlank(subjectId, previous.subjectId());
        boolean accountChanged = subjectId != null && !subjectId.isBlank() && !previous.subjectId().isBlank()
                && !subjectId.equals(previous.subjectId());
        String resolvedUsername = resolveAccountValue(username, previous.username(), accountChanged);
        String resolvedDisplayName = resolveAccountValue(displayName, previous.displayName(), accountChanged);
        String resolvedEmail = resolveAccountValue(email, previous.email(), accountChanged);
        String resolvedAvatarUrl = resolveAccountValue(avatarUrl, previous.avatarUrl(), accountChanged);
        String resolvedSessionLabel = resolveAccountValue(sessionLabel, previous.sessionLabel(), accountChanged);
        if (activeSession == null) {
            persistAccountMetadata(resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel);
            return;
        }
        installSession(new Metadata(previous.grantId(), previous.ticket(), previous.audience(), previous.scopes(), previous.assignedNode(), previous.expiresAt(),
                resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel));
        persistAccountMetadata(resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel);
        if (accountChanged) {
            notifyAuthStateChanged();
        }
    }

    public static void replaceAccountMetadata(String subjectId, String username, String displayName, String email,
                                              String avatarUrl, String sessionLabel) {
        Metadata previous = metadata();
        String resolvedSubjectId = subjectId == null ? "" : subjectId;
        String resolvedUsername = username == null ? "" : username;
        String resolvedDisplayName = displayName == null ? "" : displayName;
        String resolvedEmail = email == null ? "" : email;
        String resolvedAvatarUrl = avatarUrl == null ? "" : avatarUrl;
        String resolvedSessionLabel = sessionLabel == null ? "" : sessionLabel;
        boolean accountChanged = !resolvedSubjectId.equals(previous.subjectId());
        if (activeSession == null) {
            persistAccountMetadata(resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel);
            return;
        }
        installSession(new Metadata(previous.grantId(), previous.ticket(), previous.audience(), previous.scopes(), previous.assignedNode(), previous.expiresAt(),
                resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel));
        persistAccountMetadata(resolvedSubjectId, resolvedUsername, resolvedDisplayName, resolvedEmail, resolvedAvatarUrl, resolvedSessionLabel);
        if (accountChanged) {
            notifyAuthStateChanged();
        }
    }

    public static void addAuthStateListener(Runnable listener) {
        if (listener != null && !AUTH_STATE_LISTENERS.contains(listener)) {
            AUTH_STATE_LISTENERS.add(listener);
        }
    }

    public static void removeAuthStateListener(Runnable listener) {
        AUTH_STATE_LISTENERS.remove(listener);
    }

    public static void addTicketListener(Runnable listener) {
        if (listener != null && !TICKET_LISTENERS.contains(listener)) {
            TICKET_LISTENERS.add(listener);
        }
    }

    public static void removeTicketListener(Runnable listener) {
        TICKET_LISTENERS.remove(listener);
    }

    public static void addSessionExpiryListener(Runnable listener) {
        if (listener != null && !SESSION_EXPIRY_LISTENERS.contains(listener)) {
            SESSION_EXPIRY_LISTENERS.add(listener);
        }
    }

    public static void removeSessionExpiryListener(Runnable listener) {
        SESSION_EXPIRY_LISTENERS.remove(listener);
    }

    public static void clearLocalSession() {
        failPendingOperations(new IllegalStateException("Browser Session Expired"));
        advanceRequestGeneration();
        clearCallbacks();
        boolean authenticated = authenticated();
        installSession(null);
        demoLeaseExpiresAt = "";
        demoEditablePaths = Set.of();
        renewalInFlight = false;
        finishRenewalWaiters(null, new IllegalStateException("Browser Session Expired"));
        cancelRenewal();
        cancelDemoLeaseExpiry();
        clearBrowserSession();
        if (authenticated) {
            notifyTicketChanged();
            notifyAuthStateChanged();
        }
    }

    static void expireSession() {
        if (authenticated()) notifySessionExpiryListeners();
        clearLocalSession();
    }

    private static Metadata durableMetadata() {
        return new Metadata("", "", "", Set.of(), "", "", readAccountMetadata("subjectId"), readAccountMetadata("username"),
                readAccountMetadata("displayName"), readAccountMetadata("email"), readAccountMetadata("avatarUrl"), readAccountMetadata("sessionLabel"));
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }

    private static String resolveAccountValue(String value, String previous, boolean accountChanged) {
        return accountChanged ? value == null ? "" : value : firstNonBlank(value, previous);
    }

    private static String normalizedDemoPath(String value) {
        String path = value == null ? "" : value.strip().replace('\\', '/');
        if (path.isBlank()) return "";
        if (!path.startsWith("/")) path = "/" + path;
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return path;
    }

    public static void cancelLaunch() {
        failPendingOperations(new IllegalStateException("Browser Session Launch Cancelled"));
        advanceRequestGeneration();
        clearCallbacks();
        renewalInFlight = false;
        finishRenewalWaiters(null, new IllegalStateException("Browser Session Launch Cancelled"));
        cancelRenewal();
        cancelDemoLeaseExpiry();
    }

    public static void pollInteractiveLogin() {
        Callback callback = interactiveLoginCallback;
        if (callback == null) {
            return;
        }
        if (!loginWindowOpen()) {
            finishInteractiveLogin(callback, false, "ReStudio Login Was Cancelled");
            return;
        }
        if (interactiveLoginAttempts++ >= MAX_INTERACTIVE_LOGIN_ATTEMPTS) {
            finishInteractiveLogin(callback, false, "ReStudio Login Timed Out");
            return;
        }
        int requestId = nextId();
        interactiveLoginRequestId = requestId;
        registerCallback(requestId, new Callback() {
            @Override
            public void ready(Metadata metadata) {
                if (interactiveLoginCallback != callback) {
                    return;
                }
                if (metadata != null && metadata.ticket() != null && !metadata.ticket().isBlank()
                        && authenticated()) {
                    finishInteractiveLogin(callback, true, null);
                    callback.ready(metadata);
                    return;
                }
                scheduleInteractiveLoginPoll(1500);
            }

            @Override
            public void failed(String message) {
                if (interactiveLoginCallback != callback) {
                    return;
                }
                scheduleInteractiveLoginPoll(1500);
            }
        });
        requestLaunch(requestId, false);
    }

    private static void finishInteractiveLogin(Callback callback, boolean success, String message) {
        if (interactiveLoginCallback != callback) {
            return;
        }
        interactiveLoginCallback = null;
        interactiveLoginAttempts = 0;
        interactiveLoginRequestId = 0;
        cancelInteractiveLoginPoll();
        closeLoginWindow();
        if (!success) {
            callback.failed(message == null || message.isBlank() ? "ReStudio Login Failed" : message);
        }
    }

    private static void cancelInteractiveLogin(String message) {
        Callback callback = interactiveLoginCallback;
        interactiveLoginCallback = null;
        interactiveLoginAttempts = 0;
        if (interactiveLoginRequestId > 0) {
            removeCallback(interactiveLoginRequestId);
            interactiveLoginRequestId = 0;
        }
        cancelInteractiveLoginPoll();
        closeLoginWindow();
        if (callback != null) {
            callback.failed(message == null || message.isBlank() ? "ReStudio Login Was Cancelled" : message);
        }
    }

    private static void notifyAuthStateChanged() {
        for (Runnable listener : new ArrayList<>(AUTH_STATE_LISTENERS)) {
            listener.run();
        }
    }

    private static void notifyTicketChanged() {
        for (Runnable listener : new ArrayList<>(TICKET_LISTENERS)) {
            listener.run();
        }
    }

    private static void notifySessionExpiryListeners() {
        for (Runnable listener : new ArrayList<>(SESSION_EXPIRY_LISTENERS)) {
            listener.run();
        }
    }

    @JSBody(script = "var backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; return new URL('/api', backend).toString().replace(/\\/$/, '');")
    public static native String apiBaseUrl();

    @JSBody(script = "var backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; return new URL('/api/remotely-web/capabilities', backend).toString().replace(/\\/$/, '');")
    public static native String capabilityBaseUrl();

    @JSBody(params = {"serverId"}, script = "var backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; var url = new URL('/ws/remotely-web/resync/' + encodeURIComponent(serverId || ''), backend); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'; return url.toString();")
    public static native String reSyncUrl(String serverId);

    @JSBody(params = {"serverId"}, script = "var backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; var url = new URL('/ws/remotely-web/servers/' + encodeURIComponent(serverId || '') + '/terminal', backend); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'; return url.toString();")
    public static native String terminalUrl(String serverId);

    @JSBody(params = {"forceLogin"}, script = "try { const backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; const backendUrl = new URL(backend); const currentUrl = new URL(window.location.href); const localPage = currentUrl.protocol === 'file:' || ['localhost', '127.0.0.1', '[::1]', '::1'].includes(currentUrl.hostname); const authBackend = localPage || ['localhost', '127.0.0.1', '[::1]', '::1'].includes(backendUrl.hostname) ? 'https://restudiomc.net' : backendUrl.origin; const returnUrl = currentUrl.origin === backendUrl.origin || localPage ? new URL('/remotely-web/', authBackend) : currentUrl; const loginUrl = new URL('/api/auth/login', authBackend); loginUrl.searchParams.set('redirect_uri', returnUrl.toString()); if (forceLogin) loginUrl.searchParams.set('force_login', 'true'); return loginUrl.toString(); } catch (error) { return ''; }")
    private static native String loginUrl(boolean forceLogin);

    @JSBody(params = {"url"}, script = "const existing = window.__remotelyLoginWindow; if (existing && !existing.closed) { try { existing.focus(); } catch (error) {} return true; } const opened = window.open(url, 'remotely-login', 'popup,width=520,height=720,resizable=yes,scrollbars=yes'); if (!opened) return false; window.__remotelyLoginWindow = opened; try { opened.focus(); } catch (error) {} return true;")
    private static native boolean openLoginWindow(String url);

    @JSBody(script = "const popup = window.__remotelyLoginWindow; return !!popup && !popup.closed;")
    private static native boolean loginWindowOpen();

    @JSBody(script = "const popup = window.__remotelyLoginWindow; window.__remotelyLoginWindow = null; if (popup && !popup.closed) { try { popup.close(); } catch (error) {} }")
    private static native void closeLoginWindow();

    @JSBody(script = "const backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; const backendUrl = new URL(backend); const localPage = window.location.protocol === 'file:' || ['localhost', '127.0.0.1', '[::1]', '::1'].includes(window.location.hostname); const authBackend = localPage || ['localhost', '127.0.0.1', '[::1]', '::1'].includes(backendUrl.hostname) ? 'https://restudiomc.net' : backendUrl.origin; fetch(new URL('/api/auth/logout', authBackend).toString(), {method: 'POST', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}}).catch(function() {});")
    private static native void requestLogout();

    @JSBody(script = "if (window.__remotelyFetchControllers) { Object.keys(window.__remotelyFetchControllers).forEach(function(key) { const controller = window.__remotelyFetchControllers[key]; if (controller && typeof controller.abort === 'function') controller.abort(); delete window.__remotelyFetchControllers[key]; }); } if (window.__remotelyWebSockets) { Object.keys(window.__remotelyWebSockets).forEach(function(key) { const socket = window.__remotelyWebSockets[key]; if (socket && typeof socket.close === 'function') socket.close(1000, 'Signed Out'); delete window.__remotelyWebSockets[key]; }); } const refreshController = window.__remotelyApplicationSessionRefreshController; if (refreshController && typeof refreshController.abort === 'function') refreshController.abort(); window.__remotelyApplicationSessionRefreshController = null; window.__remotelyApplicationSessionRefresh = null; window.__remotelyBrowserSession = null;")
    private static native void clearBrowserSession();

    @JSBody(params = {"expiresAt"}, script = "if (window.__remotelySessionRenewal) window.clearTimeout(window.__remotelySessionRenewal); var expiry = Date.parse(expiresAt || ''); var delay = Number.isFinite(expiry) ? Math.max(5000, expiry - Date.now() - 30000) : 60000; window.__remotelySessionRenewal = window.setTimeout(function() { javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.renew()V').invoke(); }, delay);")
    private static native void scheduleRenewal(String expiresAt);

    @JSBody(script = "if (window.__remotelySessionRenewal) window.clearTimeout(window.__remotelySessionRenewal); window.__remotelySessionRenewal = window.setTimeout(function() { javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.renew()V').invoke(); }, 30000);")
    private static native void scheduleRenewalRetry();

    @JSBody(script = "if (window.__remotelySessionRenewal) window.clearTimeout(window.__remotelySessionRenewal); window.__remotelySessionRenewal = 0;")
    private static native void cancelRenewal();

    @JSBody(params = {"expiresAt"}, script = "if (window.__remotelyDemoLeaseExpiry) window.clearTimeout(window.__remotelyDemoLeaseExpiry); const expiry = Date.parse(expiresAt || ''); if (!Number.isFinite(expiry)) return; window.__remotelyDemoLeaseExpiry = window.setTimeout(function() { javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.demoLeaseExpired()V').invoke(); }, Math.max(0, expiry - Date.now()));")
    private static native void scheduleDemoLeaseExpiry(String expiresAt);

    @JSBody(script = "if (window.__remotelyDemoLeaseExpiry) window.clearTimeout(window.__remotelyDemoLeaseExpiry); window.__remotelyDemoLeaseExpiry = 0;")
    private static native void cancelDemoLeaseExpiry();

    @JSBody(params = "key", script = "try { return window.localStorage.getItem('remotely.session.account.' + key) || ''; } catch (e) { return ''; }")
    private static native String readAccountMetadata(String key);

    @JSBody(params = {"subjectId", "username", "displayName", "email", "avatarUrl", "sessionLabel"}, script = "try { const values = {subjectId: subjectId || '', username: username || '', displayName: displayName || '', email: email || '', avatarUrl: avatarUrl || '', sessionLabel: sessionLabel || ''}; const demo = new URL(window.location.href).searchParams.get('demo') === 'reactor'; const storage = demo ? window.sessionStorage : window.localStorage; Object.keys(values).forEach(function(key) { const storageKey = 'remotely.session.account.' + key; if (values[key]) storage.setItem(storageKey, values[key]); else storage.removeItem(storageKey); }); } catch (e) {}")
    private static native void persistAccountMetadata(String subjectId, String username, String displayName, String email, String avatarUrl, String sessionLabel);

    @JSBody(script = "try { ['subjectId', 'username', 'displayName', 'email', 'avatarUrl', 'sessionLabel'].forEach(function(key) { window.localStorage.removeItem('remotely.session.account.' + key); window.sessionStorage.removeItem('remotely.session.account.' + key); }); } catch (e) {}")
    private static native void clearAccountMetadata();

    @JSBody(params = {"delay"}, script = "if (window.__remotelyInteractiveLoginPoll) window.clearTimeout(window.__remotelyInteractiveLoginPoll); window.__remotelyInteractiveLoginPoll = window.setTimeout(function() { javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.pollInteractiveLogin()V').invoke(); }, Math.max(0, Number(delay) || 0));")
    private static native void scheduleInteractiveLoginPoll(int delay);

    @JSBody(script = "if (window.__remotelyInteractiveLoginPoll) window.clearTimeout(window.__remotelyInteractiveLoginPoll); window.__remotelyInteractiveLoginPoll = 0;")
    private static native void cancelInteractiveLoginPoll();

    private static int nextId() {
        int requestId = nextRequestId++;
        if (requestId <= 0) {
            nextRequestId = 2;
            requestId = 1;
        }
        return requestId;
    }

    private static void registerCallback(int requestId, Callback callback) {
        CALLBACKS.put(requestId, callback);
        REQUEST_GENERATIONS.put(requestId, requestGeneration);
    }

    private static Callback removeCallback(int requestId) {
        REQUEST_GENERATIONS.remove(requestId);
        PRIMARY_CALLBACKS.remove(requestId);
        PRIMARY_RESULTS.remove(requestId);
        return CALLBACKS.remove(requestId);
    }

    private static boolean isCurrentRequest(int requestId) {
        return CALLBACKS.containsKey(requestId) && requestGeneration == REQUEST_GENERATIONS.getOrDefault(requestId, -1L);
    }

    private static void clearCallbacks() {
        CALLBACKS.clear();
        PRIMARY_CALLBACKS.clear();
        PRIMARY_RESULTS.clear();
        REQUEST_GENERATIONS.clear();
    }

    private static void failPendingOperations(Throwable failure) {
        Throwable cause = failure == null ? new IllegalStateException("Browser Session Expired") : failure;
        String message = cause.getMessage() == null || cause.getMessage().isBlank() ? "Browser Session Expired" : cause.getMessage();
        List<Integer> resultIds = new ArrayList<>(PRIMARY_RESULTS.keySet());
        List<Async<Metadata>> results = new ArrayList<>(PRIMARY_RESULTS.values());
        PRIMARY_RESULTS.clear();
        for (Integer requestId : resultIds) {
            REQUEST_GENERATIONS.remove(requestId);
            CALLBACKS.remove(requestId);
        }
        for (Async<Metadata> result : results) {
            result.fail(cause);
        }
        List<Integer> callbackIds = new ArrayList<>(PRIMARY_CALLBACKS.keySet());
        List<Callback> callbacks = new ArrayList<>(PRIMARY_CALLBACKS.values());
        PRIMARY_CALLBACKS.clear();
        for (Integer requestId : callbackIds) {
            REQUEST_GENERATIONS.remove(requestId);
            CALLBACKS.remove(requestId);
        }
        Callback interactive = interactiveLoginCallback;
        cancelInteractiveLogin(message);
        for (Callback callback : callbacks) {
            if (callback != interactive) {
                callback.failed(message);
            }
        }
    }

    private static void advanceRequestGeneration() {
        requestGeneration++;
        if (requestGeneration <= 0) {
            requestGeneration = 1;
        }
    }

    @JSBody(params = {"requestId", "forceLogin"}, script = """
            function responseMessage(body, response, operation) {
                if (response.status === 429 && String(operation || '').toLowerCase().startsWith('reactor demo')) {
                    return 'The Reactor Demo Needs A Moment. Please Try Again Soon';
                }
                const text = String(body || '').trim();
                const contentType = String(response.headers.get('content-type') || '').toLowerCase();
                const fallback = operation + ' failed with status ' + response.status;
                if (!text || contentType.includes('text/html') || /<\\s*(?:!doctype|html|head|body|title)\\b/i.test(text)) {
                    return fallback;
                }
                try {
                    const payload = JSON.parse(text);
                    if (payload && typeof payload.message === 'string' && payload.message.trim()) {
                        return payload.message.trim();
                    }
                    if (payload && typeof payload.error === 'string' && payload.error.trim()) {
                        return payload.error.trim();
                    }
                } catch (error) {
                }
                return text.replace(/\\s+/g, ' ').slice(0, 240) || fallback;
            }
            const backend = window.__remotelyBackendOrigin || 'https://restudiomc.net';
            if (!backend) {
                javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.fail(ILjava/lang/String;)V').invoke(requestId, 'Remotely Web Backend Is Not Configured');
                return;
            }
            const demo = new URL(window.location.href).searchParams.get('demo') === 'reactor';
            if (demo) {
                const demoController = typeof AbortController === 'function' ? new AbortController() : null;
                const demoControllerKey = 'reactor-demo-' + requestId;
                const demoControllers = window.__remotelyFetchControllers || (window.__remotelyFetchControllers = {});
                if (demoController) demoControllers[demoControllerKey] = demoController;
                let demoFinished = false;
                const demoTimeout = window.setTimeout(function() {
                    if (demoController) demoController.abort();
                    failDemo('Reactor demo launch timed out');
                }, 30000);
                function clearDemoRequest() {
                    if (demoFinished) return false;
                    demoFinished = true;
                    window.clearTimeout(demoTimeout);
                    if (window.__remotelyFetchControllers && window.__remotelyFetchControllers[demoControllerKey] === demoController) {
                        delete window.__remotelyFetchControllers[demoControllerKey];
                    }
                    return true;
                }
                function failDemo(message) {
                    if (clearDemoRequest()) fail(message);
                }
                function demoOptions(headers) {
                    const options = {method: 'POST', credentials: 'include', cache: 'no-store', headers: headers};
                    if (demoController) options.signal = demoController.signal;
                    return options;
                }
                try {
                    fetch(new URL('/api/remotely-web/demo/challenge', backend).toString(), demoOptions({'Accept': 'application/json'})).then(function(response) {
                        return response.text().then(function(body) {
                            if (!response.ok) {
                                failDemo(responseMessage(body, response, 'Reactor demo challenge'));
                                return null;
                            }
                            try {
                                const payload = JSON.parse(body);
                                if (!payload || typeof payload.challenge !== 'string' || !payload.challenge.trim()) {
                                    failDemo('Reactor demo challenge was not valid');
                                    return null;
                                }
                                return payload.challenge.trim();
                            } catch (error) {
                                failDemo('Reactor demo challenge was not valid JSON');
                                return null;
                            }
                        });
                    }).then(function(challenge) {
                        if (!challenge || demoFinished) return null;
                        return fetch(new URL('/api/remotely-web/demo/session', backend).toString(), demoOptions({'Accept': 'application/json', 'X-Reactor-Demo-Challenge': challenge})).then(function(response) {
                            return response.text().then(function(body) {
                                if (!response.ok) {
                                    failDemo(responseMessage(body, response, 'Reactor demo session'));
                                    return null;
                                }
                                var session;
                                try {
                                    session = JSON.parse(body);
                                } catch (error) {
                                    failDemo('Reactor demo response was not valid JSON');
                                    return null;
                                }
                                const profile = session.account || {};
                                try {
                                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.startDemoLeaseExpiry(Ljava/lang/String;)V').invoke(String(session.leaseExpiresAt || ''));
                                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.demoEditablePaths(Ljava/lang/String;)V').invoke(Array.isArray(session.editablePaths) ? session.editablePaths.join('\\n') : '');
                                    javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.complete(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V').invoke(requestId, String(session.grantId || session.leaseId || ''), String(session.ticket || ''), String(session.audience || ''), Array.isArray(session.scopes) ? session.scopes.join(',') : '', String(session.nodeId || ''), String(session.expiresAt || ''), String(profile.id || session.leaseId || ''), String(profile.username || 'reactor-demo'), String(profile.displayName || 'Reactor Demo'), '', '', String(session.sessionLabel || 'Reactor Demo'));
                                } finally {
                                    clearDemoRequest();
                                }
                                return null;
                            });
                        });
                    }).catch(function(error) {
                        if (error && error.name === 'AbortError') failDemo('Reactor demo launch timed out');
                        else failDemo(String(error && (error.message || error) || 'Reactor demo launch failed'));
                    });
                } catch (error) {
                    failDemo(String(error && (error.message || error) || 'Reactor demo launch failed'));
                }
                return;
            }
            function refreshApplicationSession() {
                const existing = window.__remotelyApplicationSessionRefresh;
                if (existing) return existing;
                const controller = typeof AbortController === 'function' ? new AbortController() : null;
                const options = {method: 'POST', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}};
                if (controller) options.signal = controller.signal;
                const request = fetch(new URL('/api/auth/refresh', backend).toString(), options).then(function(response) {
                    return response.text().then(function(body) {
                        if (response.ok) return null;
                        const failure = new Error(responseMessage(body, response, 'Session refresh'));
                        failure.status = response.status;
                        throw failure;
                    });
                });
                window.__remotelyApplicationSessionRefresh = request;
                window.__remotelyApplicationSessionRefreshController = controller;
                request.then(function() {
                    if (window.__remotelyApplicationSessionRefresh === request) {
                        window.__remotelyApplicationSessionRefresh = null;
                        window.__remotelyApplicationSessionRefreshController = null;
                    }
                }, function() {
                    if (window.__remotelyApplicationSessionRefresh === request) {
                        window.__remotelyApplicationSessionRefresh = null;
                        window.__remotelyApplicationSessionRefreshController = null;
                    }
                });
                return request;
            }
            function unauthenticated() {
                javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.unauthenticated(I)V').invoke(requestId);
            }
            function fail(message) {
                javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.fail(ILjava/lang/String;)V').invoke(requestId, message);
            }
            function refreshAndRetry(retriedAfterRefresh) {
                if (retriedAfterRefresh) {
                    unauthenticated();
                    return null;
                }
                return refreshApplicationSession().then(function() {
                    return requestLaunch(true);
                }).catch(function(error) {
                    if (error && error.status === 401) {
                        unauthenticated();
                    } else {
                        fail(String(error && (error.message || error) || 'Session refresh failed'));
                    }
                    return null;
                });
            }
            function requestLaunch(retriedAfterRefresh) {
                const launchUrl = new URL('/api/remotely-web/launch', backend);
                if (forceLogin) launchUrl.searchParams.set('force_login', 'true');
                return fetch(launchUrl.toString(), {method: 'POST', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}}).then(function(response) {
                    return response.text().then(function(body) {
                        if (!response.ok) {
                            if (response.status === 401) return refreshAndRetry(retriedAfterRefresh);
                            fail(responseMessage(body, response, 'Launch request'));
                            return null;
                        }
                        var payload;
                        try {
                            payload = JSON.parse(body);
                        } catch (error) {
                            fail('Launch response was not valid JSON');
                            return null;
                        }
                        return fetch(new URL('/api/remotely-web/browser/session', backend).toString(), {method: 'POST', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}}).then(function(sessionResponse) {
                            return sessionResponse.text().then(function(sessionBody) {
                                if (!sessionResponse.ok) {
                                    if (sessionResponse.status === 401) return refreshAndRetry(retriedAfterRefresh);
                                    fail(responseMessage(sessionBody, sessionResponse, 'Browser session request'));
                                    return null;
                                }
                                var session;
                                try {
                                    session = JSON.parse(sessionBody);
                                } catch (error) {
                                    fail('Browser session response was not valid JSON');
                                    return null;
                                }
                                const profile = session.account || session.user || payload.account || payload.user || {};
                                javaMethods.get('redxax.oxy.remotely.web.platform.BrowserLaunchSession.complete(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V').invoke(requestId, String(payload.grantId || ''), String(session.ticket || ''), String(session.audience || payload.audience || ''), Array.isArray(session.scopes) ? session.scopes.join(',') : (Array.isArray(payload.scopes) ? payload.scopes.join(',') : ''), String(session.nodeId || payload.assignedNode || ''), String(session.expiresAt || payload.expiresAt || ''), String(profile.id || profile.userId || payload.userId || ''), String(profile.username || ''), String(profile.displayName || ''), String(profile.email || ''), String(profile.avatarUrl || ''), String(session.sessionLabel || session.label || payload.sessionLabel || ''));
                                return null;
                            });
                        });
                    });
                }).catch(function(error) {
                    fail(String(error && (error.message || error) || 'Browser launch failed'));
                });
            }
            fetch(new URL('/api/remotely-web/demo/session', backend).toString(), {method: 'DELETE', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}}).catch(function() { return null; }).then(function() { requestLaunch(false); });
            """)
    private static native void requestLaunch(int requestId, boolean forceLogin);

    @JSBody(script = "try { const backend = window.__remotelyBackendOrigin || 'https://restudiomc.net'; fetch(new URL('/api/remotely-web/demo/session', backend).toString(), {method: 'DELETE', credentials: 'include', cache: 'no-store', headers: {'Accept': 'application/json'}}); } catch (e) {}")
    private static native void requestDemoEnd();

    @JSBody(script = "if (typeof window.__remotelyBackendOrigin === 'string' && window.__remotelyBackendOrigin) return window.__remotelyBackendOrigin; const current = new URL(window.location.href); const isLocal = function(host) { return host === 'localhost' || host === '127.0.0.1' || host === '[::1]' || host === '::1'; }; const config = window.__REMOTELY_WEB_CONFIG__ && typeof window.__REMOTELY_WEB_CONFIG__.backendOrigin === 'string' ? window.__REMOTELY_WEB_CONFIG__.backendOrigin.trim() : ''; const meta = document.querySelector('meta[name=\"remotely-backend-origin\"]'); const configured = config || (meta && typeof meta.content === 'string' ? meta.content.trim() : '') || current.searchParams.get('backendOrigin') || ''; const explicit = configured.length > 0; let target; try { target = new URL(explicit ? configured : 'https://restudiomc.net', current.href); } catch (error) { return ''; } if (target.username || target.password || target.search || target.hash || target.pathname !== '/') return ''; if (target.protocol !== 'https:' && !(target.protocol === 'http:' && isLocal(target.hostname))) return ''; window.__remotelyBackendOrigin = target.origin; return target.origin;")
    public static native String backendOrigin();

    @JSBody(script = "const current = new URL(window.location.href); const mode = current.searchParams.get('preview'); return mode === 'unauthenticated';")
    private static native boolean localUnauthenticatedPreview();

    @JSBody(script = "return new URL(window.location.href).searchParams.get('demo') === 'reactor';")
    public static native boolean demoRequested();

    public interface Callback {
        void ready(Metadata metadata);

        void failed(String message);
    }

    public record Metadata(String grantId, String ticket, String audience, Set<String> scopes, String assignedNode, String expiresAt,
                           String subjectId, String username, String displayName, String email, String avatarUrl, String sessionLabel) {
        public Metadata(String grantId, String ticket, String audience, Set<String> scopes, String assignedNode, String expiresAt) {
            this(grantId, ticket, audience, scopes, assignedNode, expiresAt, "", "", "", "", "", "");
        }

        public Metadata {
            grantId = grantId == null ? "" : grantId;
            ticket = ticket == null ? "" : ticket;
            audience = audience == null ? "" : audience;
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
            assignedNode = assignedNode == null ? "" : assignedNode;
            expiresAt = expiresAt == null ? "" : expiresAt;
            subjectId = subjectId == null ? "" : subjectId;
            username = username == null ? "" : username;
            displayName = displayName == null ? "" : displayName;
            email = email == null ? "" : email;
            avatarUrl = avatarUrl == null ? "" : avatarUrl;
            sessionLabel = sessionLabel == null ? "" : sessionLabel;
        }

        public boolean sameAuthority(Metadata other) {
            return other != null && subjectId.equals(other.subjectId()) && audience.equals(other.audience())
                && assignedNode.equals(other.assignedNode()) && scopes.equals(other.scopes());
        }

        public boolean demo() {
            return scopes.contains("remotely.demo");
        }
    }
}
