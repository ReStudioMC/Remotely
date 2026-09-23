package redxax.oxy.remotely.data.flow;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class CreationJournalCodecTest {
    @Test
    void journalRetainsEveryRecoveryFieldAndExactLongIdentity() throws Exception {
        String source = """
            {
              "schemaVersion": 3,
              "generation": 9007199254740993,
              "entries": [
                {
                  "serverId": "serverId",
                  "resourceType": "resourceType",
                  "id": "id",
                  "resourceTemplate": "resourceTemplate",
                  "payloadJson": "payloadJson",
                  "locator": "locator",
                  "payloadHash": "payloadHash",
                  "payloadRequestId": "payloadRequestId",
                  "payloadMutationId": "payloadMutationId",
                  "metadataRequestId": "metadataRequestId",
                  "metadataMutationId": "metadataMutationId",
                  "metadataType": "metadataType",
                  "metadataId": "metadataId",
                  "metadataName": "metadataName",
                  "metadataPath": "metadataPath",
                  "metadataParentPath": "metadataParentPath",
                  "metadataSortOrder": 7,
                  "folder": true,
                  "commandContext": "commandContext",
                  "commandGraphJson": "commandGraphJson",
                  "commandGraphHash": "commandGraphHash",
                  "commandGraphRequestId": "commandGraphRequestId",
                  "commandGraphMutationId": "commandGraphMutationId",
                  "triggerRequestId": "triggerRequestId",
                  "triggerBindingsJson": "triggerBindingsJson",
                  "triggerBindingsHash": "triggerBindingsHash",
                  "triggerExpectedBindingEpoch": 9007199254740993,
                  "triggerExpectedBindingHash": "triggerExpectedBindingHash",
                  "commandGraphCommitted": true,
                  "triggerCommitted": true,
                  "phase": "phase",
                  "payloadSettlement": "payloadSettlement",
                  "payloadCommitted": true,
                  "attempts": 7,
                  "sequence": 9007199254740993,
                  "resumePhase": "resumePhase",
                  "commandGraphBaseRevision": 9007199254740993,
                  "commandGraphBaseHash": "commandGraphBaseHash",
                  "commandGraphBaseGeneration": 9007199254740993,
                  "corePayloadKind": "corePayloadKind"
                }
              ]
            }
            """;
        assertEquals(JsonParser.parseString(source), encode(decode(source)));
    }

    @Test
    void oldJournalDefaultsMatchTheExistingPersistedSchema() throws Exception {
        String source = """
            {"schemaVersion":1,"generation":4,"entries":[{"serverId":"server","resourceType":"flow","id":"flow"}]}
            """;
        Class<?> type = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CreationJournal");
        JsonObject expected = new Gson().toJsonTree(new Gson().fromJson(source, type)).getAsJsonObject();
        assertEquals(expected, encode(decode(source)));
    }

    private Object decode(String source) throws Exception {
        Class<?> type = Class.forName("redxax.oxy.remotely.data.flow.FlowManager$CreationJournal");
        Method read = type.getDeclaredMethod("read", String.class);
        read.setAccessible(true);
        return read.invoke(null, source);
    }

    private JsonObject encode(Object journal) throws Exception {
        Method json = journal.getClass().getDeclaredMethod("json");
        json.setAccessible(true);
        return (JsonObject) json.invoke(journal);
    }
}
