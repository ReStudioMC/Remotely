package redxax.oxy.remotely.web.platform;

import redxax.oxy.remotely.host.ApplicationHost;
import restudio.rebase.restudio.ReStudioAccount;
import restudio.rebase.restudio.ReStudioMembership;
import restudio.rebase.restudio.SessionState;
import restudio.rebase.restudio.api.models.FeedbackModels;
import restudio.rebase.restudio.community.ReStudioAccountManagementProvider;
import restudio.rebase.restudio.community.ReStudioCommunityProvider;
import restudio.rebase.restudio.community.ReStudioCommunityClient;
import restudio.rebase.restudio.community.FeedbackSync;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.http.HttpTransport;
import restudio.rescreen.util.Identifier;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

public final class BrowserCommunityProvider implements ReStudioCommunityProvider, ReStudioAccountManagementProvider {
    private final RemotelyCommunityHttpTransport http;
    private final RemotelyCommunitySessionTransport session;
    private final ReStudioCommunityClient client;

    public BrowserCommunityProvider(HttpTransport transport) {
        this(transport, null);
    }

    public BrowserCommunityProvider(HttpTransport transport, ApplicationHost host) {
        http = new RemotelyCommunityHttpTransport(Objects.requireNonNull(transport, "transport"));
        session = new RemotelyCommunitySessionTransport(host);
        client = new ReStudioCommunityClient(http, session, new RemotelyCommunityStorageTransport(),
                ReStudioCommunityClient.Configuration.remotely());
    }

    public BrowserCommunityProvider(HttpTransport transport, ApplicationHost host, BrowserLaunchSession.Metadata launch) {
        this(transport, host);
        if (launch != null && BrowserLaunchSession.authenticated()) {
            ReStudioAccount account = new ReStudioAccount();
            account.id = launch.subjectId();
            account.username = launch.username();
            account.displayName = launch.displayName();
            account.email = launch.email();
            account.avatarUrl = launch.avatarUrl();
            client.applyAccount(account);
        }
    }

    @Override
    public Async<String> feedbackRequest(String method, String suffix, String body) { return client.feedbackRequest(method, suffix, body); }
    @Override
    public FeedbackSync feedbackSync() { return client.feedbackSync(); }
    @Override
    public Async<FeedbackModels.File> uploadThreadFile(String id, String commentId, String name, String contentType, byte[] bytes) {
        return client.uploadThreadFile(id, commentId, name, contentType, bytes);
    }

    @Override
    public Async<FeedbackModels.File> uploadThreadFile(String id, String commentId, String name, String contentType, byte[] bytes, String requestId) {
        return client.uploadThreadFile(id, commentId, name, contentType, bytes, requestId);
    }
    @Override
    public Async<byte[]> downloadThreadFile(String postId, String fileId) { return client.downloadThreadFile(postId, fileId); }

    @Override
    public Async<Void> markNotificationRead(FeedbackModels.Notification notification) { return client.markNotificationRead(notification); }

    public void close() {
        client.close();
        session.close();
    }

    @Override
    public Async<String> collaborationRequest(String method, String path, String body) {
        return client.collaborationRequest(method, path, body);
    }

    @Override
    public boolean isAuthenticated() {
        return client.isAuthenticated();
    }

    @Override
    public boolean isAdmin() {
        return client.isAdmin();
    }

    @Override
    public boolean supportsFeedbackAdministration() {
        return client.supportsFeedbackAdministration();
    }

    @Override
    public boolean ownsFeedbackPost(FeedbackModels.Post post) {
        return client.ownsFeedbackPost(post);
    }

    @Override
    public boolean isFeedbackPostAuthor(FeedbackModels.Post post, FeedbackModels.Comment comment) {
        return client.isFeedbackPostAuthor(post, comment);
    }

    @Override
    public String userId() {
        return client.userId();
    }

    @Override
    public String username() {
        return client.username();
    }

    @Override
    public String displayName() {
        return client.displayName();
    }

    @Override
    public String email() {
        return client.email();
    }

    @Override
    public String avatarUrl() {
        return client.avatarUrl();
    }

    @Override
    public void addAccountChangeListener(Runnable listener) {
        client.addAccountChangeListener(listener);
    }

    @Override
    public void removeAccountChangeListener(Runnable listener) {
        client.removeAccountChangeListener(listener);
    }

    @Override
    public SessionState sessionState() {
        return client.sessionState();
    }

    @Override
    public void login(boolean forceLogin, Runnable onSuccess, Consumer<Throwable> onFailure) {
        client.login(forceLogin, onSuccess, onFailure);
    }

    @Override
    public Async<Boolean> retryConnection() {
        return client.retryConnection();
    }

    @Override
    public void cancelLogin() {
        client.cancelLogin();
    }

    @Override
    public void setProfileSetupVisible(boolean visible) {
        client.setProfileSetupVisible(visible);
    }

    @Override
    public Async<ReStudioAccount> getAccount() {
        return client.getAccount();
    }

    @Override
    public Async<Identifier> loadAvatarId(String userId, String avatarUrl) {
        return client.loadAvatarId(userId, avatarUrl);
    }

    @Override
    public void applyAccount(ReStudioAccount account) {
        client.applyAccount(account);
    }

    @Override
    public Async<Boolean> isUsernameAvailable(String username) {
        return client.isUsernameAvailable(username);
    }

    @Override
    public Async<ReStudioAccount> updateAccount(String username, String displayName) {
        return client.updateAccount(username, displayName);
    }

    @Override
    public Async<List<FeedbackModels.Channel>> getChannels() {
        return client.getChannels();
    }

    @Override
    public Async<List<FeedbackModels.Category>> getCategories(String channelId) {
        return client.getCategories(channelId);
    }

    @Override
    public Async<List<FeedbackModels.Post>> getPosts(String categoryId) {
        return client.getPosts(categoryId);
    }

    @Override
    public boolean supportsFeedbackVoting() {
        return client.supportsFeedbackVoting();
    }

    @Override
    public Async<FeedbackModels.Vote> votePost(String postId, boolean enabled) {
        return client.votePost(postId, enabled);
    }

    @Override
    public Async<List<FeedbackModels.Comment>> getComments(String postId) {
        return client.getComments(postId);
    }

    @Override
    public Async<Void> createPost(String categoryId, String title, String content, String rating) {
        return client.createPost(categoryId, title, content, rating);
    }

    @Override
    public Async<Void> createComment(String postId, String content) {
        return client.createComment(postId, content);
    }

    @Override
    public Async<Void> updatePostStatus(String postId, String status) {
        return client.updatePostStatus(postId, status);
    }

    @Override
    public Async<Void> deletePost(String postId) {
        return client.deletePost(postId);
    }

    @Override
    public Async<Void> deleteComment(String commentId) {
        return client.deleteComment(commentId);
    }

    @Override
    public Async<Void> banUser(String userId, String reason) {
        return client.banUser(userId, reason);
    }

    @Override
    public Async<List<FeedbackModels.Notification>> getNotifications() {
        return client.getNotifications();
    }

    @Override
    public Async<Void> markNotificationRead(String id) {
        return client.markNotificationRead(id);
    }

    @Override
    public Async<ReStudioAccount> uploadAvatar(byte[] bytes, String contentType, String filename) {
        return client.uploadAvatar(bytes, contentType, filename);
    }

    @Override
    public Async<ReStudioAccount> deleteAvatar() {
        return client.deleteAvatar();
    }

    @Override
    public Async<List<ApplicationSession>> getApplicationSessions() {
        return client.getApplicationSessions();
    }

    @Override
    public Async<Void> revokeApplicationSession(UUID sessionId) {
        return client.revokeApplicationSession(sessionId);
    }

    @Override
    public Async<Void> revokeOtherApplicationSessions() {
        return client.revokeOtherApplicationSessions();
    }

    @Override
    public Async<List<ReStudioMembership>> getMemberships() {
        return client.getMemberships();
    }

    @Override
    public Async<ReStudioMembership> refreshMembership(UUID id) {
        return client.refreshMembership(id);
    }

    @Override
    public Async<ReStudioMembership> setMembershipRenewal(UUID id, boolean enabled) {
        return client.setMembershipRenewal(id, enabled);
    }

    static boolean membershipFenceCurrent(long fenceGeneration, String fenceSubjectId, long sessionGeneration,
                                          String sessionSubjectId, boolean providerCurrent) {
        return fenceGeneration >= 0 && fenceGeneration == sessionGeneration && fenceSubjectId != null
                && !fenceSubjectId.isBlank() && Objects.equals(fenceSubjectId, sessionSubjectId) && providerCurrent;
    }
}
