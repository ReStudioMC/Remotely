package com.google.gson;

import com.google.gson.internal.LazilyParsedNumber;
import restudio.rescreen.util.JsonTreeParser;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public final class Gson {
    public String toJson(Object src) {
        return JsonTreeParser.write(toJsonTree(src));
    }

    public String toJson(JsonElement jsonElement) {
        return JsonTreeParser.write(jsonElement == null ? JsonNull.INSTANCE : jsonElement);
    }

    public void toJson(Object src, Appendable writer) {
        try {
            writer.append(toJson(src));
        } catch (IOException exception) {
            throw new JsonIOException(exception);
        }
    }

    public void toJson(JsonElement jsonElement, Appendable writer) {
        try {
            writer.append(toJson(jsonElement));
        } catch (IOException exception) {
            throw new JsonIOException(exception);
        }
    }

    public JsonElement toJsonTree(Object src) {
        return encode(src);
    }

    public JsonElement toJsonTree(Object src, Type typeOfSrc) {
        return encode(src);
    }

    public <T> T fromJson(String json, Class<T> classOfT) {
        return fromJson(parse(json), classOfT);
    }

    public <T> T fromJson(String json, Type typeOfT) {
        return fromJson(parse(json), typeOfT);
    }

    public <T> T fromJson(JsonElement json, Class<T> classOfT) {
        return fromJson(json, (Type) classOfT);
    }

    @SuppressWarnings("unchecked")
    public <T> T fromJson(JsonElement json, Type typeOfT) {
        if (typeOfT == null) {
            return null;
        }
        try {
            return (T) decode(json, typeOfT);
        } catch (RuntimeException exception) {
            throw new JsonSyntaxException(exception);
        }
    }

    private static JsonElement parse(String json) {
        if (json == null || json.isBlank()) {
            return JsonNull.INSTANCE;
        }
        try {
            return JsonTreeParser.parse(json);
        } catch (RuntimeException exception) {
            throw new JsonSyntaxException(exception);
        }
    }

    private JsonElement encode(Object src) {
        if (src == null) {
            return JsonNull.INSTANCE;
        }
        if (src instanceof JsonElement element) {
            return element;
        }
        if (src instanceof String text) {
            return new JsonPrimitive(text);
        }
        if (src instanceof Character character) {
            return new JsonPrimitive(character);
        }
        if (src instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (src instanceof Number number) {
            return new JsonPrimitive(number);
        }
        if (src instanceof UUID uuid) {
            return new JsonPrimitive(uuid.toString());
        }
        if (src instanceof Enum<?> value) {
            return new JsonPrimitive(value.name());
        }
        if (src instanceof Map<?, ?> map) {
            JsonObject object = new JsonObject();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                object.add(String.valueOf(entry.getKey()), encode(entry.getValue()));
            }
            return object;
        }
        if (src instanceof Iterable<?> iterable) {
            JsonArray array = new JsonArray();
            for (Object item : iterable) {
                array.add(encode(item));
            }
            return array;
        }
        if (src instanceof boolean[] values) {
            JsonArray array = new JsonArray();
            for (boolean value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof byte[] values) {
            JsonArray array = new JsonArray();
            for (byte value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof short[] values) {
            JsonArray array = new JsonArray();
            for (short value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof int[] values) {
            JsonArray array = new JsonArray();
            for (int value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof long[] values) {
            JsonArray array = new JsonArray();
            for (long value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof float[] values) {
            JsonArray array = new JsonArray();
            for (float value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof double[] values) {
            JsonArray array = new JsonArray();
            for (double value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof char[] values) {
            JsonArray array = new JsonArray();
            for (char value : values) {
                array.add(new JsonPrimitive(value));
            }
            return array;
        }
        if (src instanceof Object[] values) {
            JsonArray array = new JsonArray();
            for (Object value : values) {
                array.add(encode(value));
            }
            return array;
        }
        throw new JsonIOException("Unsupported JSON value");
    }

    private Object decode(JsonElement element, Type type) {
        if (element == null || element.isJsonNull()) {
            return type instanceof Class<?> classType && classType.isPrimitive() ? defaultPrimitive(classType) : null;
        }
        if (type instanceof Class<?> classType) {
            return decodeClass(element, classType);
        }
        if (type instanceof ParameterizedType parameterized) {
            Type raw = parameterized.getRawType();
            if (!(raw instanceof Class<?> rawClass)) {
                return decodeUntyped(element);
            }
            Type[] arguments = parameterized.getActualTypeArguments();
            if (Map.class.isAssignableFrom(rawClass)) {
                return decodeMap(element, arguments.length > 1 ? arguments[1] : Object.class, rawClass);
            }
            if (Collection.class.isAssignableFrom(rawClass)) {
                return decodeCollection(element, rawClass, arguments.length > 0 ? arguments[0] : Object.class);
            }
            return decodeClass(element, rawClass);
        }
        if (type instanceof GenericArrayType genericArray) {
            return decodeArray(element, genericArray.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            Type[] bounds = wildcard.getUpperBounds();
            return decode(element, bounds.length == 0 ? Object.class : bounds[0]);
        }
        return decodeUntyped(element);
    }

    private Object decodeClass(JsonElement element, Class<?> type) {
        if (type == JsonElement.class) {
            return element;
        }
        if (type == JsonObject.class) {
            return element.getAsJsonObject();
        }
        if (type == JsonArray.class) {
            return element.getAsJsonArray();
        }
        if (type == JsonPrimitive.class) {
            return element.getAsJsonPrimitive();
        }
        if (type == JsonNull.class) {
            return JsonNull.INSTANCE;
        }
        if (type == Object.class) {
            return decodeUntyped(element);
        }
        if (type == String.class) {
            return element.isJsonPrimitive() ? element.getAsString() : JsonTreeParser.write(element);
        }
        if (type == Character.class || type == char.class) {
            String text = element.getAsString();
            return text.isEmpty() ? '\0' : text.charAt(0);
        }
        if (type == Boolean.class || type == boolean.class) {
            return element.getAsBoolean();
        }
        if (type == UUID.class) {
            return UUID.fromString(element.getAsString());
        }
        if (type.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object value = Enum.valueOf((Class) type, element.getAsString());
            return value;
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return decodeNumber(element, type);
        }
        if (type.isArray()) {
            return decodeArray(element, type.getComponentType());
        }
        if (Map.class.isAssignableFrom(type)) {
            return decodeMap(element, Object.class, type);
        }
        if (Collection.class.isAssignableFrom(type)) {
            return decodeCollection(element, type, Object.class);
        }
        if (element.isJsonObject() || element.isJsonArray()) {
            return decodeUntyped(element);
        }
        throw new JsonSyntaxException("Unsupported JSON type");
    }

    private Object decodeUntyped(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                map.put(entry.getKey(), decodeUntyped(entry.getValue()));
            }
            return map;
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement child : element.getAsJsonArray()) {
                values.add(decodeUntyped(child));
            }
            return values;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            return new LazilyParsedNumber(primitive.getAsString());
        }
        return primitive.getAsString();
    }

    private Object decodeNumber(JsonElement element, Class<?> type) {
        if (type == Integer.class || type == int.class) {
            return element.getAsInt();
        }
        if (type == Long.class || type == long.class) {
            return element.getAsLong();
        }
        if (type == Short.class || type == short.class) {
            return element.getAsShort();
        }
        if (type == Byte.class || type == byte.class) {
            return element.getAsByte();
        }
        if (type == Float.class || type == float.class) {
            return element.getAsFloat();
        }
        if (type == Double.class || type == double.class) {
            return element.getAsDouble();
        }
        if (type == BigDecimal.class) {
            return element.getAsBigDecimal();
        }
        if (type == BigInteger.class) {
            return element.getAsBigInteger();
        }
        if (type == LazilyParsedNumber.class || type == Number.class) {
            return new LazilyParsedNumber(element.getAsString());
        }
        return element.getAsNumber();
    }

    private Map<String, Object> decodeMap(JsonElement element, Type valueType, Class<?> mapType) {
        Map<String, Object> map = SortedMap.class.isAssignableFrom(mapType) || mapType == TreeMap.class
            ? new TreeMap<>()
            : new LinkedHashMap<>();
        if (!element.isJsonObject()) {
            return map;
        }
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            map.put(entry.getKey(), decode(entry.getValue(), valueType));
        }
        return map;
    }

    private Collection<Object> decodeCollection(JsonElement element, Class<?> collectionType, Type itemType) {
        Collection<Object> values;
        if (SortedSet.class.isAssignableFrom(collectionType) || collectionType == TreeSet.class) {
            values = new TreeSet<>();
        } else if (Set.class.isAssignableFrom(collectionType)) {
            values = new LinkedHashSet<>();
        } else {
            values = new ArrayList<>();
        }
        if (!element.isJsonArray()) {
            if (!element.isJsonNull()) {
                values.add(decode(element, itemType));
            }
            return values;
        }
        for (JsonElement child : element.getAsJsonArray()) {
            values.add(decode(child, itemType));
        }
        return values;
    }

    private Object decodeArray(JsonElement element, Type componentType) {
        List<Object> values = new ArrayList<>();
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                values.add(decode(child, componentType));
            }
        } else if (!element.isJsonNull()) {
            values.add(decode(element, componentType));
        }
        Class<?> componentClass = componentType instanceof Class<?> classType ? classType : Object.class;
        Object array = Array.newInstance(componentClass, values.size());
        for (int index = 0; index < values.size(); index++) {
            Array.set(array, index, values.get(index));
        }
        return array;
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return 0;
    }
}
