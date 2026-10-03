package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.host.ApplicationHost;
import restudio.rebase.restudio.ReStudioAccount;
import restudio.rebase.restudio.community.ReStudioCommunitySessionTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class RemotelyCommunitySessionTransport implements ReStudioCommunitySessionTransport {
    private final ApplicationHost host;
    private final List<Runnable> listeners = new ArrayList<>();
    private final Runnable sessionListener = this::notifyListeners;

    RemotelyCommunitySessionTransport(ApplicationHost host) {
        this.host = host;
        BrowserLaunchSession.addAuthStateListener(sessionListener);
    }

    @Override
    public boolean authenticated() {
        return BrowserLaunchSession.authenticated();
    }

    @Override
    public String authorityKey() {
        return BrowserLaunchSession.authorityKey();
    }

    @Override
    public ReStudioAccount account() {
        BrowserLaunchSession.Metadata metadata = BrowserLaunchSession.metadata();
        ReStudioAccount value = new ReStudioAccount();
        value.id = metadata.subjectId();
        value.username = metadata.username();
        value.displayName = metadata.displayName();
        value.email = metadata.email();
        value.avatarUrl = metadata.avatarUrl();
        return value.id.isBlank() && value.username.isBlank() && value.displayName.isBlank() ? null : value;
    }

    @Override
    public void addStateListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    @Override
    public void removeStateListener(Runnable listener) {
        listeners.remove(listener);
    }

    @Override
    public void login(boolean forceLogin, Runnable onSuccess, Consumer<Throwable> onFailure) {
        if (BrowserLaunchSession.authenticated()) {
            if (onSuccess != null) onSuccess.run();
            return;
        }
        BrowserLaunchSession.Callback callback = new BrowserLaunchSession.Callback() {
            @Override
            public void ready(BrowserLaunchSession.Metadata metadata) {
                if (metadata != null && BrowserLaunchSession.authenticated()) {
                    if (onSuccess != null) onSuccess.run();
                    return;
                }
                if (onFailure != null) onFailure.accept(new IllegalStateException("ReStudio Login Was Not Completed"));
            }

            @Override
            public void failed(String message) {
                if (host != null) host.signIn(host.getCurrentScreen());
                if (onFailure != null) onFailure.accept(new IllegalStateException(
                        message == null || message.isBlank() ? "ReStudio Login Failed" : message));
            }
        };
        if (!BrowserLaunchSession.beginInteractiveLogin(forceLogin, callback)
                && onFailure != null) onFailure.accept(new IllegalStateException("Sign In Window Could Not Be Opened"));
    }

    @Override
    public void cancelLogin() {
        BrowserLaunchSession.cancelLaunch();
    }

    @Override
    public void expire() {
        BrowserLaunchSession.expireSession();
    }

    @Override
    public void applyAccount(ReStudioAccount account) {
        BrowserLaunchSession.Metadata metadata = BrowserLaunchSession.metadata();
        BrowserLaunchSession.replaceAccountMetadata(account == null ? "" : account.id,
                account == null ? "" : account.username, account == null ? "" : account.displayName,
                account == null ? "" : account.email, account == null ? "" : account.avatarUrl,
                metadata.sessionLabel());
    }

    void close() {
        BrowserLaunchSession.removeAuthStateListener(sessionListener);
        listeners.clear();
    }

    private void notifyListeners() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }
}
