package redxax.oxy.remotely.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import redxax.oxy.remotely.network.protocol.NetworkCommand;
import redxax.oxy.remotely.network.protocol.NetworkOperationStatus;
import redxax.oxy.remotely.network.protocol.NetworkProtocolCodec;
import restudio.rescreen.platform.Async;
import restudio.rescreen.platform.Clock;
import restudio.rescreen.platform.TaskScheduler;
import restudio.rescreen.util.JsonTreeParser;

import java.net.URLEncoder;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HostedNetworkClient {
    private static final String ROOT = "/hosted-networks";
    private static final Duration POLL_DELAY = Duration.ofSeconds(2);
    private static final Duration OBSERVATION_TIMEOUT = Duration.ofMinutes(30);
    private final Transport transport;
    private final Clock clock;
    private final NetworkProtocolCodec protocol = new NetworkProtocolCodec();

    public HostedNetworkClient(Transport transport) {
        this(transport, Clock.system());
    }

    public HostedNetworkClient(Transport transport, Clock clock) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Async<NetworkOperationStatus> submit(NetworkCommand command) {
        Objects.requireNonNull(command, "command");
        return submit(command, bodyFor(command));
    }

    private Async<NetworkOperationStatus> submit(NetworkCommand command, String body) {
        return transport.request("POST", ROOT + "/commands", body).thenApply(response ->
                status(response, command.networkId(), command.requestId(), command.type()));
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand command, TaskScheduler scheduler, BooleanSupplier current) {
        return execute(command, scheduler, current, null);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand command, TaskScheduler scheduler, BooleanSupplier current,
                                                Consumer<NetworkOperationStatus> progress) {
        return executePrepared(command, bodyFor(command), scheduler, current, progress);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand.Create command, String body, TaskScheduler scheduler, BooleanSupplier current) {
        return execute(command, body, scheduler, current, null);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand.Create command, String body, TaskScheduler scheduler, BooleanSupplier current,
                                                Consumer<NetworkOperationStatus> progress) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(body, "body");
        if (!command.equals(createFromBody(body))) throw new IllegalArgumentException("Pending Network Command Does Not Match");
        return executePrepared(command, body, scheduler, current, progress);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand.Attach command, String body, TaskScheduler scheduler, BooleanSupplier current) {
        return execute(command, body, scheduler, current, null);
    }

    public Async<NetworkOperationStatus> execute(NetworkCommand.Attach command, String body, TaskScheduler scheduler, BooleanSupplier current,
                                                Consumer<NetworkOperationStatus> progress) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(body, "body");
        if (!command.equals(attachFromBody(body))) throw new IllegalArgumentException("Pending Server Command Does Not Match");
        return executePrepared(command, body, scheduler, current, progress);
    }

    private Async<NetworkOperationStatus> executePrepared(NetworkCommand command, String body, TaskScheduler scheduler,
                                                          BooleanSupplier current, Consumer<NetworkOperationStatus> progress) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(current, "current");
        return new Observation(command, scheduler, current, progress).start(body);
    }

    public Async<NetworkOperationStatus> observe(String networkId, JsonObject admitted, TaskScheduler scheduler,
                                                 BooleanSupplier current, Consumer<NetworkOperationStatus> progress) {
        try {
            Objects.requireNonNull(scheduler, "scheduler");
            Objects.requireNonNull(current, "current");
            Map<String, Object> value = object(Objects.requireNonNull(admitted, "Network Status Is Required"));
            if (!(value.get("requestId") instanceof String requestId)) {
                throw new IllegalArgumentException("Hosted Network Request Is Missing");
            }
            NetworkOperationStatus status = status(value, networkId(networkId), requestId, null);
            return new Observation(status, scheduler, current, progress).start(null);
        } catch (Throwable failure) {
            return Async.failed(failure);
        }
    }

    static String bodyFor(NetworkCommand command) {
        return commandBody(new NetworkProtocolCodec().encode(Objects.requireNonNull(command, "command")));
    }

    static NetworkCommand.Create createFromBody(String body) {
        Map<String, Object> value = pendingBody(body);
        Object members = value.get("members");
        if (!(members instanceof List<?> list)) throw new IllegalArgumentException("Pending Network Members Are Invalid");
        for (Object member : list) {
            if (!(member instanceof Map<?, ?> entry)) throw new IllegalArgumentException("Pending Network Member Is Invalid");
            memberLongs(entry);
        }
        NetworkCommand command = new NetworkProtocolCodec().decodeCommand(value);
        if (!(command instanceof NetworkCommand.Create create)) throw new IllegalArgumentException("Pending Network Command Is Not Creation");
        return create;
    }

    static NetworkCommand.Attach attachFromBody(String body) {
        Map<String, Object> value = pendingBody(body);
        Map<String, Object> member = nested(value, "member");
        if (member == null) throw new IllegalArgumentException("Pending Server Member Is Invalid");
        memberLongs(member);
        NetworkCommand command = new NetworkProtocolCodec().decodeCommand(value);
        if (!(command instanceof NetworkCommand.Attach attach)) throw new IllegalArgumentException("Pending Network Command Is Not Attachment");
        return attach;
    }

    private static Map<String, Object> pendingBody(String body) {
        JsonElement parsed = JsonTreeParser.parse(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Pending Network Command Is Invalid");
        Map<String, Object> value = object(parsed.getAsJsonObject());
        convertLong(value, "expectedRevision");
        return value;
    }

    private static void memberLongs(Map<?, ?> member) {
        Object source = member.get("source");
        if (!(source instanceof Map<?, ?> sourceMap)) throw new IllegalArgumentException("Pending Network Source Is Invalid");
        if (!"DRAFT".equals(sourceMap.get("kind"))) return;
        @SuppressWarnings("unchecked") Map<String, Object> draft = (Map<String, Object>) sourceMap;
        convertLong(draft, "expectedRevision");
        convertLong(nested(draft, "installer"), "ramMiB");
        convertLong(nested(draft, "installer"), "cpuQuotaPercent");
        convertLong(nested(draft, "runtime"), "ramMiB");
        convertLong(nested(draft, "runtime"), "cpuQuotaPercent");
        convertLong(nested(draft, "retained"), "diskMiB");
        convertLong(nested(draft, "retained"), "backupMiB");
    }

    private final class Observation {
        private final NetworkCommand command;
        private final NetworkOperationStatus admitted;
        private final String networkId;
        private final String requestId;
        private final NetworkCommand.Type type;
        private final TaskScheduler scheduler;
        private final BooleanSupplier current;
        private final Consumer<NetworkOperationStatus> progress;
        private final Async<NetworkOperationStatus> result = Async.pending();
        private Async<NetworkOperationStatus> request;
        private TaskScheduler.ScheduledTask poll;
        private TaskScheduler.ScheduledTask timeout;
        private long startedAt;

        private Observation(NetworkCommand command, TaskScheduler scheduler, BooleanSupplier current,
                            Consumer<NetworkOperationStatus> progress) {
            this.command = command;
            this.admitted = null;
            this.networkId = command.networkId();
            this.requestId = command.requestId();
            this.type = command.type();
            this.scheduler = scheduler;
            this.current = current;
            this.progress = progress == null ? ignored -> {} : progress;
        }

        private Observation(NetworkOperationStatus admitted, TaskScheduler scheduler, BooleanSupplier current,
                            Consumer<NetworkOperationStatus> progress) {
            this.command = null;
            this.admitted = admitted;
            this.networkId = admitted.networkId();
            this.requestId = admitted.requestId();
            this.type = admitted.command();
            this.scheduler = scheduler;
            this.current = current;
            this.progress = progress == null ? ignored -> {} : progress;
        }

        private synchronized Async<NetworkOperationStatus> start(String body) {
            result.whenComplete((status, failure) -> stop());
            try {
                startedAt = clock.millis();
                if (!active()) return result;
                if (command == null) {
                    observe(admitted, null);
                } else {
                    request = submit(command, body);
                    request.whenComplete(this::observe);
                }
                if (!result.isDone()) {
                    long remaining = Math.max(0L, OBSERVATION_TIMEOUT.toMillis() - (clock.millis() - startedAt));
                    timeout = scheduler.schedule(this::expire, Duration.ofMillis(remaining));
                }
            } catch (Throwable failure) {
                result.fail(failure);
            }
            return result;
        }

        private boolean active() {
            if (result.isDone()) return false;
            if (!current.getAsBoolean()) {
                result.fail(new IllegalStateException("Account Changed. Reopen The Network"));
                return false;
            }
            if (clock.millis() - startedAt >= OBSERVATION_TIMEOUT.toMillis()) {
                expire();
                return false;
            }
            return true;
        }

        private synchronized void observe(NetworkOperationStatus status, Throwable failure) {
            try {
                if (!active()) return;
                if (failure != null) {
                    result.fail(failure);
                    return;
                }
                progress.accept(Objects.requireNonNull(status, "Network Status Is Required"));
                if (result.isDone()) return;
                switch (status.state()) {
                    case SUCCEEDED -> result.complete(status);
                    case ROLLED_BACK -> {
                        if (type == NetworkCommand.Type.ROLLBACK) result.complete(status);
                        else result.fail(new OperationFailure(status));
                    }
                    case FAILED, NEEDS_REVIEW -> result.fail(new OperationFailure(status));
                    default -> poll = scheduler.schedule(this::poll, POLL_DELAY);
                }
            } catch (Throwable error) {
                result.fail(error);
            }
        }

        private synchronized void poll() {
            poll = null;
            try {
                if (!active()) return;
                request = operation(networkId, UUID.fromString(requestId), type);
                request.whenComplete(this::observe);
            } catch (Throwable failure) {
                result.fail(failure);
            }
        }

        private synchronized void expire() {
            result.fail(new IllegalStateException(command == null
                    ? "Network Progress Check Timed Out. Reopen The Network To Check Its Outcome"
                    : "Network Progress Check Timed Out. Resume The Saved Request To Check Its Outcome"));
        }

        private synchronized void stop() {
            if (poll != null) poll.cancel();
            if (timeout != null) timeout.cancel();
            if (request != null && !request.isDone()) request.cancel();
            poll = null;
            timeout = null;
            request = null;
        }
    }

    public Async<NetworkOperationStatus> operation(String networkId, UUID requestId) {
        return operation(networkId, requestId, null);
    }

    public Async<NetworkOperationStatus> operation(String networkId, UUID requestId, NetworkCommand.Type command) {
        String network = networkId(networkId);
        UUID request = Objects.requireNonNull(requestId, "requestId");
        return transport.request("GET", ROOT + "/" + segment(network) + "/operations/" + request, null)
                .thenApply(response -> status(response, network, request.toString(), command));
    }

    private NetworkOperationStatus status(String response, String networkId, String requestId, NetworkCommand.Type command) {
        JsonElement parsed = JsonTreeParser.parse(response);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Hosted Network Status Is Invalid");
        Map<String, Object> value = object(parsed.getAsJsonObject());
        return status(value, networkId, requestId, command);
    }

    private NetworkOperationStatus status(Map<String, Object> value, String networkId, String requestId, NetworkCommand.Type command) {
        Object actualCommand = value.get("command");
        if (!(actualCommand instanceof String name)) throw new IllegalArgumentException("Hosted Network Command Is Missing");
        NetworkCommand.Type type;
        try {
            type = NetworkCommand.Type.valueOf(name);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Hosted Network Command Is Invalid", failure);
        }
        if (command != null && type != command) throw new IllegalArgumentException("Hosted Network Command Does Not Match");
        statusLongs(value);
        NetworkOperationStatus status = protocol.decodeStatus(value);
        if (!requestId.equals(status.requestId()) || !networkId.equals(status.networkId()) || type != status.command()) {
            throw new IllegalArgumentException("Hosted Network Status Identity Does Not Match");
        }
        if (status.job() != null && !networkId.equals(status.job().networkId())
                || status.lifecycleJob() != null && !networkId.equals(status.lifecycleJob().networkId())) {
            throw new IllegalArgumentException("Hosted Network Job Identity Does Not Match");
        }
        return status;
    }

    private static String commandBody(Map<String, Object> command) {
        JsonObject value = jsonObject(command);
        stringLong(value, "expectedRevision");
        JsonElement members = value.get("members");
        if (members != null && members.isJsonArray()) {
            for (JsonElement member : members.getAsJsonArray()) draftLongs(member);
        }
        draftLongs(value.get("member"));
        return JsonTreeParser.write(value);
    }

    private static void draftLongs(JsonElement member) {
        if (member == null || !member.isJsonObject()) return;
        JsonElement source = member.getAsJsonObject().get("source");
        if (source == null || !source.isJsonObject()) return;
        JsonObject draft = source.getAsJsonObject();
        JsonElement kind = draft.get("kind");
        if (kind == null || !kind.isJsonPrimitive() || !"DRAFT".equals(kind.getAsString())) return;
        stringLong(draft, "expectedRevision");
        computeLongs(draft.get("installer"));
        computeLongs(draft.get("runtime"));
        JsonElement retained = draft.get("retained");
        if (retained != null && retained.isJsonObject()) {
            stringLong(retained.getAsJsonObject(), "diskMiB");
            stringLong(retained.getAsJsonObject(), "backupMiB");
        }
    }

    private static void computeLongs(JsonElement compute) {
        if (compute == null || !compute.isJsonObject()) return;
        stringLong(compute.getAsJsonObject(), "ramMiB");
        stringLong(compute.getAsJsonObject(), "cpuQuotaPercent");
    }

    private static void stringLong(JsonObject value, String key) {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        }
        String number = item.getAsString();
        long parsed = exactLong(number);
        value.addProperty(key, Long.toString(parsed));
    }

    private static void statusLongs(Map<String, Object> value) {
        convertLong(value, "networkRevision");
        convertLong(value, "createdAt");
        convertLong(value, "updatedAt");
        Map<String, Object> job = nested(value, "job");
        if (job != null) {
            convertLong(job, "networkRevision");
            convertLong(job, "createdAt");
            convertLong(job, "updatedAt");
        }
        Map<String, Object> lifecycle = nested(value, "lifecycleJob");
        if (lifecycle != null) {
            convertLong(lifecycle, "networkRevision");
            convertLong(lifecycle, "createdAt");
            convertLong(lifecycle, "updatedAt");
            Object steps = lifecycle.get("steps");
            if (!(steps instanceof List<?> list)) throw new IllegalArgumentException("Hosted Network Lifecycle Steps Are Invalid");
            for (Object item : list) {
                if (!(item instanceof Map<?, ?>)) throw new IllegalArgumentException("Hosted Network Lifecycle Step Is Invalid");
                @SuppressWarnings("unchecked") Map<String, Object> step = (Map<String, Object>) item;
                convertLong(step, "startedAt");
                convertLong(step, "completedAt");
            }
        }
    }

    private static Map<String, Object> nested(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (value == null) return null;
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        @SuppressWarnings("unchecked") Map<String, Object> nested = (Map<String, Object>) value;
        return nested;
    }

    private static void convertLong(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String text)) throw new IllegalArgumentException("Hosted Network " + key + " Is Invalid");
        value.put(key, exactLong(text));
    }

    private static long exactLong(String value) {
        if (value == null || !value.matches("-?(0|[1-9][0-9]*)")) {
            throw new IllegalArgumentException("Hosted Network Integer Is Invalid");
        }
        try {
            long parsed = Long.parseLong(value);
            if (!Long.toString(parsed).equals(value)) throw new IllegalArgumentException("Hosted Network Integer Is Invalid");
            return parsed;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Hosted Network Integer Is Outside Its Range", failure);
        }
    }

    private static JsonObject jsonObject(Map<String, ?> values) {
        JsonObject result = new JsonObject();
        values.forEach((key, value) -> result.add(key, json(value)));
        return result;
    }

    private static JsonElement json(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Map<?, ?> source) {
            JsonObject result = new JsonObject();
            source.forEach((key, item) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("Hosted Network Object Key Is Invalid");
                result.add(name, json(item));
            });
            return result;
        }
        if (value instanceof List<?> items) {
            JsonArray result = new JsonArray();
            items.forEach(item -> result.add(json(item)));
            return result;
        }
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Boolean flag) return new JsonPrimitive(flag);
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return new JsonPrimitive((Number) value);
        }
        if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new IllegalArgumentException("Hosted Network Number Is Invalid");
            return new JsonPrimitive(number);
        }
        throw new IllegalArgumentException("Hosted Network Value Is Invalid");
    }

    private static Map<String, Object> object(JsonObject value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.entrySet().forEach(entry -> result.put(entry.getKey(), read(entry.getValue())));
        return result;
    }

    private static Object read(JsonElement value) {
        if (value == null || value.isJsonNull()) return null;
        if (value.isJsonObject()) return object(value.getAsJsonObject());
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) result.add(read(item));
            return result;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) return primitive.getAsString();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return exactLong(primitive.getAsString());
        throw new IllegalArgumentException("Hosted Network Value Is Invalid");
    }

    private static String networkId(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim()) || value.length() > 255
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Hosted Network Identity Is Invalid");
        }
        return value;
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @FunctionalInterface
    public interface Transport {
        Async<String> request(String method, String path, String body);
    }

    public static final class OperationFailure extends IllegalStateException {
        private final NetworkOperationStatus status;

        public OperationFailure(NetworkOperationStatus status) {
            super(status.message().isBlank() ? "Network Operation Needs Attention" : status.message());
            this.status = Objects.requireNonNull(status, "status");
        }

        public NetworkOperationStatus status() {
            return status;
        }
    }
}
