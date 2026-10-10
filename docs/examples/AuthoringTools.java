import dev.gray.companion.core.*;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.json.*;
import kotlin.Unit;
import java.nio.file.*;
import java.io.*;
import java.util.*;

public class AuthoringTools {
    static Json json = LibraryFormat.INSTANCE.getJson();
    static LinkedHashMap<String, JsonElement> types = new LinkedHashMap<>();
    static JsonObject obj(Map<String, JsonElement> map) { return new JsonObject(map); }
    static JsonPrimitive str(String value) { return JsonElementKt.JsonPrimitive(value); }
    static JsonPrimitive bool(boolean value) { return JsonElementKt.JsonPrimitive(value); }
    static JsonArray arr(Collection<? extends JsonElement> values) { return new JsonArray(new ArrayList<>(values)); }
    static Map<String, JsonElement> map(Object... pairs) {
        LinkedHashMap<String, JsonElement> result = new LinkedHashMap<>();
        for (int i=0; i<pairs.length; i+=2) result.put((String)pairs[i], (JsonElement)pairs[i+1]);
        return result;
    }
    static String name(SerialDescriptor descriptor) { return descriptor.getSerialName().replaceAll("\\?$", ""); }
    static String type(SerialDescriptor descriptor) {
        String kind = descriptor.getKind().toString(), result;
        switch(kind) {
            case "LIST": result = "array<" + type(descriptor.getElementDescriptor(0)) + ">"; break;
            case "MAP": result = "object<string," + type(descriptor.getElementDescriptor(1)) + ">"; break;
            default: result = name(descriptor).replace("dev.gray.companion.core.", "");
        }
        return result + (descriptor.isNullable() ? " | null" : "");
    }
    static void visit(SerialDescriptor descriptor) {
        String n = name(descriptor), kind = descriptor.getKind().toString();
        if (n.startsWith("kotlinx.serialization.json.")) return;
        if (kind.equals("LIST") || kind.equals("MAP")) { for(int i=0;i<descriptor.getElementsCount();i++) visit(descriptor.getElementDescriptor(i)); return; }
        if (!kind.equals("CLASS") && !kind.equals("OBJECT") && !kind.equals("ENUM")) return;
        if (types.containsKey(n)) return;
        types.put(n, JsonNull.INSTANCE);
        List<JsonElement> fields = new ArrayList<>();
        for(int i=0;i<descriptor.getElementsCount();i++) {
            if (kind.equals("ENUM")) fields.add(str(descriptor.getElementName(i)));
            else {
                SerialDescriptor child = descriptor.getElementDescriptor(i);
                fields.add(obj(map("name",str(descriptor.getElementName(i)),"type",str(type(child)),
                    "optional",bool(descriptor.isElementOptional(i)),"nullable",bool(child.isNullable()))));
                visit(child);
            }
        }
        types.put(n,obj(map("kind",str(kind),kind.equals("ENUM") ? "values" : "fields",arr(fields))));
    }
    static KSerializer serializer(String simple) throws Exception {
        Class<?> c = Class.forName("dev.gray.companion.core." + simple);
        Object companion = c.getField("Companion").get(null);
        return (KSerializer)companion.getClass().getMethod("serializer").invoke(companion);
    }
    static JsonElement scrub(JsonElement element) {
        if(element instanceof JsonPrimitive p && p.isString() && p.getContent().matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) return str("<自动生成ID，引用目标须自行显式指定>");
        if(element instanceof JsonArray a) return arr(a.stream().map(AuthoringTools::scrub).toList());
        if(element instanceof JsonObject o) { LinkedHashMap<String, JsonElement> m=new LinkedHashMap<>(); o.forEach((k,v)->m.put(k,scrub(v))); return obj(m); }
        return element;
    }
    static void catalog(Path destination) throws Exception {
        String[] classes = {"CharacterCard","MainCall","PromptPackage","CallGroup","UserPersona","PromptTag","ModelProfile","ApiConnection","ConfigurationExtension","Workflow","WorkflowNode","PromptBlock","HistoryConfig","ReasoningParsingConfig","CardDefinition","RegexOutputCopy","PluginCode","AutomaticOptionsConfig","GlobalMemoryConfig","MemoryStageRules","HistoryPromptRead","CardResource","CardPlacement","CardSnapshot","AutomaticOptionSpec","AutomaticOptionsLayout"};
        Map<String,JsonElement> defaults = new LinkedHashMap<>();
        for(String simple:classes) {
            KSerializer s=serializer(simple); visit(s.getDescriptor());
            try { Object value=Class.forName("dev.gray.companion.core."+simple).getConstructor().newInstance(); defaults.put(simple,scrub(json.encodeToJsonElement(s,value))); }
            catch(NoSuchMethodException required) { }
        }
        List<JsonElement> actions = new ArrayList<>();
        for(ActionDescriptor d: ActionRegistry.Companion.defaults(null).descriptors()) actions.add(json.encodeToJsonElement(ActionDescriptor.Companion.serializer(), d));
        JsonObject root=obj(map("format",str("companion.authoring-reference.v1"),"purpose",str("文档附件，不是可导入资源或新应用协议"),
            "app",str("velune 0.57-preview / code225; revision67"),"resourceWire",JsonElementKt.JsonPrimitive(LibraryFormat.CURRENT_VERSION),
            "execution",JsonElementKt.JsonPrimitive(ExecutionSemantics.CURRENT),"configFormat",str("companion.config.v1"),"zipFormat",str("companion.bundle.v1"),
            "limitations",str("Serializer descriptors expose field names, enum values, optionality and nullability, not full validation rules or JSON Schema. typedDefaults are constructor values, not always valid authoring examples; generated IDs are placeholders. Compatibility fields may remain serializable but are not recommended for new resources. Actions come from ActionRegistry.defaults(). See AI_IMPORT_GUIDE.txt and source validators."),"types",obj(types),"typedDefaults",obj(defaults),"actions",arr(actions)));
        if (destination.toAbsolutePath().getParent() != null) Files.createDirectories(destination.toAbsolutePath().getParent()); Files.writeString(destination,root.toString());
        System.out.println("Catalog: " + types.size() + " types, " + actions.size() + " actions");
    }
    static void validate(String[] paths) throws Exception {
        for(String path:paths) {
            ResourceArchiveResult read;
            try(InputStream in=Files.newInputStream(Path.of(path))) {
                read=ResourceArchiveImport.INSTANCE.read(in, Files.createTempDirectory("guide-validate-").toFile(),Path.of(path).getFileName().toString(),
                    new ResourceArchiveLimits(),()->Unit.INSTANCE,(bytes,mime)->{throw new IllegalArgumentException("No avatar expected");});
            }
            if(read.getDocument()==null || read.getIssues().stream().anyMatch(Issue::getError)) throw new IllegalStateException("Archive rejected " + path + ": " + read.getIssues());
            Library base=LibraryDefaults.INSTANCE.create();
            ConfigurationPreview preview=ConfigurationCommands.INSTANCE.preview(read.getDocument(),base,l->Collections.emptyList());
            if(preview.getPlan()==null || preview.getIssues().stream().anyMatch(Issue::getError)) throw new IllegalStateException("Preview rejected " + path + ": " + preview.getIssues());
            ConfigurationApplyResult applied=ConfigurationCommands.INSTANCE.apply(preview.getPlan(),base,l->Collections.emptyList());
            if(applied.getLibrary()==null || applied.getIssues().stream().anyMatch(Issue::getError)) throw new IllegalStateException("Apply rejected " + path + ": " + applied.getIssues());
            System.out.println("PASS " + Path.of(path).getFileName() + " resources=" + preview.getChanges().size() + " settings=" + preview.getSettings().size());

        }
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("catalog")) catalog(Path.of(args[1]));
        else if(args[0].equals("all")) { catalog(Path.of(args[1])); validate(Arrays.copyOfRange(args,2,args.length)); }
        else validate(Arrays.copyOfRange(args,1,args.length));
    }
}





