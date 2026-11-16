import java.nio.file.*;
import java.util.*;
import org.jetbrains.kotlin.cli.jvm.compiler.*;
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys;
import org.jetbrains.kotlin.cli.common.messages.MessageCollector;
import org.jetbrains.kotlin.config.CompilerConfiguration;
import org.jetbrains.kotlin.psi.KtPsiFactory;
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer;
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;

/** Syntax only. Android symbol resolution, type checking and Compose compilation still need Gradle/SDK. */
public class CheckKotlinSyntax {
    public static void main(String[] args) throws Exception {
        var disposable = Disposer.newDisposable();
        try {
            var configuration = new CompilerConfiguration();
            configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.Companion.getNONE());
            var environment = KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES);
            var factory = new KtPsiFactory(environment.getProject(), false);
            int checked = 0;
            for (String arg : args) {
                var path = Path.of(arg);
                var file = factory.createFile(path.getFileName().toString(), Files.readString(path));
                var errors = PsiTreeUtil.collectElementsOfType(file, PsiErrorElement.class);
                if (!errors.isEmpty()) throw new IllegalStateException(path + ": " + errors);
                checked++;
            }
            System.out.println("PASS Kotlin parser: " + checked + " files (syntax only, not Android compilation)");
        } finally { Disposer.dispose(disposable); }
    }
}
