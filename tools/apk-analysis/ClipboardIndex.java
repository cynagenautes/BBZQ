import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.*;
import com.android.tools.smali.dexlib2.iface.instruction.*;
import com.android.tools.smali.dexlib2.iface.reference.*;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ClipboardIndex {
    static String descriptor(MethodReference m) {
        return m.getDefiningClass()+"->"+m.getName()+"("+String.join("", m.getParameterTypes())+")"+m.getReturnType();
    }
    public static void main(String[] args) throws Exception {
        var container = DexFileFactory.loadDexContainer(new File(args[0]), Opcodes.getDefault());
        var targets = args.length > 2 ? new HashSet<>(Files.readAllLines(Path.of(args[2]))) : Set.<String>of();
        var calls = new ArrayList<Map<String,Object>>();
        var strings = new ArrayList<Map<String,Object>>();
        var namedClasses = new TreeMap<String,String>();
        var helpers = new ArrayList<Map<String,Object>>();
        var listeners = new ArrayList<Map<String,Object>>();
        var searchInputs = new ArrayList<Map<String,Object>>();
        int classes = 0, methods = 0;
        for (String entry : container.getDexEntryNames()) {
            var dex = container.getEntry(entry).getDexFile();
            for (var cls : dex.getClasses()) {
                classes++;
                if (cls.getInterfaces().contains("Landroid/content/ClipboardManager$OnPrimaryClipChangedListener;")) {
                    var fields = new ArrayList<String>();
                    for (var field : cls.getFields()) fields.add(field.getType());
                    listeners.add(Map.of("dex", entry, "class", cls.getType(), "fieldTypes", fields));
                }
                if (cls.getType().toLowerCase(Locale.ROOT).contains("search") &&
                    Set.of("Landroid/widget/EditText;", "Landroidx/appcompat/widget/AppCompatEditText;").contains(cls.getSuperclass())) {
                    searchInputs.add(Map.of("dex", entry, "class", cls.getType(), "superclass", cls.getSuperclass()));
                }
                if (cls.getType().toLowerCase(Locale.ROOT).contains("clipboard")) namedClasses.put(cls.getType(),entry);
                for (var method : cls.getMethods()) {
                    methods++;
                    if ((method.getAccessFlags() & 8) != 0 && method.getReturnType().equals("Ljava/lang/String;") &&
                        method.getParameterTypes().size() == 1 && method.getParameterTypes().get(0).toString().equals("Landroid/content/ClipboardManager;")) {
                        helpers.add(Map.of("dex",entry,"method",descriptor(method),"accessFlags",method.getAccessFlags()));
                    }
                    var code = method.getImplementation();
                    if (code == null) continue;
                    int offset = 0;
                    for (var insn : code.getInstructions()) {
                        if (insn instanceof ReferenceInstruction refInsn) {
                            var ref = refInsn.getReference();
                            if (ref instanceof MethodReference target) {
                                String owner = target.getDefiningClass();
                                if (targets.contains(owner) || (targets.isEmpty() &&
                                    (owner.toLowerCase(Locale.ROOT).contains("clipboard") ||
                                    target.getName().toLowerCase(Locale.ROOT).contains("clipboard") ||
                                    Set.of("getPrimaryClip", "hasPrimaryClip", "setPrimaryClip", "clearPrimaryClip", "getPrimaryClipDescription").contains(target.getName())))) {
                                    calls.add(Map.of("dex",entry,"caller",descriptor(method),"callerAccessFlags",method.getAccessFlags(),"target",descriptor(target),"offset",offset,"opcode",insn.getOpcode().name));
                                }
                            } else if (targets.isEmpty() && ref instanceof StringReference sr) {
                                var str = sr.getString();
                                if (str.toLowerCase(Locale.ROOT).contains("clipboard") || str.startsWith("getPrimaryClip:") || str.contains("剪贴") ||
                                    Set.of("getPrimaryClip", "hasPrimaryClip", "getPrimaryClipDescription", "addPrimaryClipChangedListener", "removePrimaryClipChangedListener").contains(str)) {
                                    strings.add(Map.of("dex",entry,"method",descriptor(method),"offset",offset,"value",str));
                                }
                            }
                        }
                        offset += insn.getCodeUnits();
                    }
                }
            }
            System.err.println("Scanned "+entry);
        }
        var result = Map.of("dex",container.getDexEntryNames(),"classCount",classes,"methodCount",methods,
                            "calls",calls,"strings",strings,"clipboardNamedClasses",namedClasses,"readHelperShapes",helpers,
                            "clipboardListenerClasses",listeners,"searchInputClasses",searchInputs);
        try (var out = Files.newBufferedWriter(Path.of(args[1]))) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result,out);
        }
        System.out.println("Scanned "+classes+" classes, "+methods+" methods; "+calls.size()+" matching references.");
    }
}
