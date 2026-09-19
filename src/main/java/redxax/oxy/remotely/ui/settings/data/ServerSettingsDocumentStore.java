package redxax.oxy.remotely.ui.settings.data;

import restudio.rescreen.platform.Async;

import java.util.List;

public interface ServerSettingsDocumentStore {
    Async<Document> read(String relativePath);

    Async<Void> write(String relativePath, String content);

    default Async<List<Entry>> list(String relativePath) {
        return Async.completed(List.of());
    }

    record Entry(String name, boolean directory) {
        public Entry {
            name = name == null ? "" : name;
        }
    }

    record Document(boolean exists, String content) {
        public Document {
            content = content == null ? "" : content;
        }

        public static Document missing() {
            return new Document(false, "");
        }
    }
}
