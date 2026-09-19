package redxax.oxy.remotely.metadata;

import restudio.rescreen.platform.Async;
import restudio.resync.metadata.MetadataBundleId;

public interface MetadataTransport {
    Async<ManifestResponse> resolve(MetadataRepository.Request request, String etag);

    Async<byte[]> bundle(MetadataBundleId bundleId);

    record ManifestResponse(Status status, String etag, byte[] bytes) {
        public ManifestResponse {
            status = status == null ? Status.RESOLVED : status;
            etag = etag == null ? "" : etag;
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    enum Status {
        RESOLVED,
        NOT_MODIFIED
    }
}
