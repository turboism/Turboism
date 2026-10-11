package dev.turboism.validation.modelimport3d.host;
import dev.turboism.sdk.cubism.*;
import java.util.*;
public final class TaskIdentityFailureTest {
    static HostProbe.DocumentBinding binding(String doc,String snap,String live,String reason) {
        var d=doc==null?null:new DocumentSnapshot(doc,"fixture","fixture.cmo3",Optional.empty(),
            Optional.of(new ModelSnapshot(snap,"fixture",List.of(),List.of(),List.of(),List.of())));
        return new HostProbe.DocumentBinding(d,null,live,reason);
    }
    public static void main(String[] args) {
        HostProbe.requireTaskBinding(binding("doc","snapshot","native","READY"),"doc","snapshot","native",
            d->{throw new AssertionError("valid binding logged as failure");});
        var first=new LinkedHashMap<String,Object>();int count=0;
        for(var b:List.of(binding("other","snapshot","native","READY"),binding("doc","other","native","READY"),
                binding("doc","snapshot","other","READY"),binding(null,null,null,"NO_MODEL_DOCUMENT"),
                binding("doc","snapshot","native","IDENTITY_CHANGED"))) {
            var seen=new ArrayList<Map<String,Object>>();
            try {HostProbe.requireTaskBinding(b,"doc","snapshot","native",d->{seen.add(d);first.putIfAbsent("first",d);});throw new AssertionError("guard bypass");}
            catch(IllegalStateException expected) {
                if(seen.size()!=1 || !seen.get(0).get("observed").equals(b.diagnostic())
                    || !seen.get(0).get("expected").equals(Map.of("documentId","doc","snapshotModelId","snapshot","editorModelId","native"))
                    || !expected.getMessage().equals(seen.get(0).get("failure")))throw new AssertionError("lost failure identity");
            }
            count++;
        }
        var original=(Map<?,?>)first.get("first");
        if(!((Map<?,?>)original.get("observed")).get("documentId").equals("other"))throw new AssertionError("recovery overwrote first failure");
        System.out.println("PASS stable identity + "+count+" rejection diagnostics + first-failure retention; no host execution");
    }
}
