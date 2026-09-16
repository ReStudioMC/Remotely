package redxax.oxy.remotely.data.flow;

import redxax.oxy.remotely.flow.registry.NodeDefinition;
import restudio.resync.flow.graph.GraphNode;
import restudio.resync.flow.graph.PinValue;
import restudio.resync.flow.graph.RepeatableBinding;
import restudio.resync.flow.graph.RepeatableElement;
import restudio.resync.flow.identity.PinId;
import restudio.resync.flow.identity.RepeatableElementId;
import restudio.resync.flow.identity.RepeatableGroupId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class CoreRepeatableUiProjection {
    private CoreRepeatableUiProjection() {
    }

    public static Projection project(GraphNode node, NodeDefinition definition) {
        if (node == null || definition == null) {
            return Projection.empty();
        }
        LinkedHashMap<RepeatableGroupId, List<NodeDefinition.PinDefinition>> members = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        List<NodeDefinition.PinDefinition> pins = new ArrayList<>(definition.getInputs());
        pins.addAll(definition.getOutputs());
        for (NodeDefinition.PinDefinition pin : pins) {
            NodeDefinition.RepeatablePin repeatable = pin.getRepeatable();
            if (repeatable == null) {
                continue;
            }
            try {
                RepeatableGroupId groupId = RepeatableGroupId.of(repeatable.getGroupId());
                members.computeIfAbsent(groupId, ignored -> new ArrayList<>()).add(pin);
            } catch (RuntimeException exception) {
                issues.add("Invalid repeatable group for pin " + pin.getId().value());
            }
        }
        Map<RepeatableGroupId, RepeatableBinding> bindings = new LinkedHashMap<>();
        for (RepeatableBinding binding : node.repeatables()) {
            bindings.put(binding.groupId(), binding);
        }
        LinkedHashMap<RepeatableGroupId, Group> groups = new LinkedHashMap<>();
        members.forEach((groupId, groupMembers) -> {
            NodeDefinition.RepeatablePin metadata = groupMembers.getFirst().getRepeatable();
            boolean consistent = groupMembers.stream().map(NodeDefinition.PinDefinition::getRepeatable)
                .allMatch(value -> sameMetadata(metadata, value));
            RepeatableBinding binding = bindings.remove(groupId);
            if (!consistent || binding != null && binding.ordered() != metadata.isOrdered()) {
                issues.add("Inconsistent repeatable group " + groupId.canonicalText());
                return;
            }
            List<Element> elements = binding == null ? List.of() : binding.elements().stream()
                .map(CoreRepeatableUiProjection::element).toList();
            if (elements.size() > metadata.getMaxItems()) {
                issues.add("Repeatable group exceeds its maximum: " + groupId.canonicalText());
                return;
            }
            groups.put(groupId, new Group(groupId, metadata.getMinItems(), metadata.getMaxItems(),
                metadata.getItemLabel(), metadata.isOrdered(), groupMembers, elements));
        });
        bindings.keySet().forEach(groupId -> issues.add("Unknown repeatable group " + groupId.canonicalText()));
        return new Projection(groups, issues);
    }

    private static boolean sameMetadata(NodeDefinition.RepeatablePin left, NodeDefinition.RepeatablePin right) {
        return right != null && Objects.equals(left.getGroupId(), right.getGroupId())
            && left.getMinItems() == right.getMinItems() && left.getMaxItems() == right.getMaxItems()
            && Objects.equals(left.getItemLabel(), right.getItemLabel()) && left.isOrdered() == right.isOrdered();
    }

    private static Element element(RepeatableElement value) {
        return new Element(value.elementId(), value.values());
    }

    public record PinEndpoint(PinId pinId, RepeatableElementId elementId) {
        public PinEndpoint {
            pinId = Objects.requireNonNull(pinId, "Pin ID is required");
        }

        public static PinEndpoint plain(PinId pinId) {
            return new PinEndpoint(pinId, null);
        }
    }

    public record Element(RepeatableElementId elementId, Map<PinId, PinValue> values) {
        public Element {
            elementId = Objects.requireNonNull(elementId, "Repeatable element ID is required");
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
        }

        public Optional<PinValue> value(PinId pinId) {
            return Optional.ofNullable(values.get(pinId));
        }
    }

    public record Group(RepeatableGroupId groupId, int minimum, int maximum, String itemLabel, boolean ordered,
                        List<NodeDefinition.PinDefinition> members, List<Element> elements) {
        public Group {
            groupId = Objects.requireNonNull(groupId, "Repeatable group ID is required");
            if (minimum < 0 || maximum < minimum) {
                throw new IllegalArgumentException("Repeatable group bounds are invalid");
            }
            itemLabel = itemLabel == null ? "" : itemLabel;
            members = List.copyOf(members == null ? List.of() : members);
            elements = List.copyOf(elements == null ? List.of() : elements);
        }

        public Optional<Element> element(RepeatableElementId elementId) {
            return elements.stream().filter(value -> value.elementId().equals(elementId)).findFirst();
        }
    }

    public record Projection(Map<RepeatableGroupId, Group> groups, List<String> issues) {
        public Projection {
            groups = Collections.unmodifiableMap(new LinkedHashMap<>(groups == null ? Map.of() : groups));
            issues = List.copyOf(issues == null ? List.of() : issues);
        }

        public static Projection empty() {
            return new Projection(Map.of(), List.of());
        }

        public Optional<Group> group(String groupId) {
            try {
                return Optional.ofNullable(groups.get(RepeatableGroupId.of(groupId)));
            } catch (RuntimeException exception) {
                return Optional.empty();
            }
        }

        public Optional<Group> group(RepeatableGroupId groupId) {
            return Optional.ofNullable(groups.get(groupId));
        }

        public boolean available() {
            return issues.isEmpty();
        }
    }
}
