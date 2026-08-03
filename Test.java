import java.nio.file.*;
import java.util.*;
import java.nio.file.attribute.*;
public class Test {
    public static void main(String[] args) throws Exception {
        Path targetFile = Paths.get("./.runtime/security/truststores/test.p12");
        Files.createDirectories(targetFile.getParent());
        Files.write(targetFile, new byte[]{1,2,3});
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(targetFile, perms);
        } catch (UnsupportedOperationException e) {
            System.out.println("UnsupportedOperationException caught!");
        } catch (Exception e) {
            System.out.println("Other exception: " + e.getClass().getName());
        }
        System.out.println("Done!");
    }
}
