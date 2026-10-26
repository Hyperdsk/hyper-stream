// Signs Hyper Stream's desktop release files, so the app's updater can tell they come from this
// project (see appCore/.../update/UpdateSignature.kt). Runs straight from source with JDK 17:
//
//   java scripts/SignRelease.java generate        prints a new key pair: keep the private key secret
//                                                 (GitHub secret HS_UPDATE_SIGNING_KEY, and the same
//                                                 variable on the Mac that runs release.sh); put the
//                                                 public key in UpdateSignature.PUBLIC_KEY
//   java scripts/SignRelease.java sign FILE...    writes FILE.sig for each, with HS_UPDATE_SIGNING_KEY
//   java scripts/SignRelease.java verify PUBLIC_KEY FILE...
//
// What is signed must match UpdateSignature.message: "hyperstream-update-v1\n<file name>\n<sha256 hex>\n".

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public class SignRelease {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) usage();
        switch (args[0]) {
            case "generate" -> {
                var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                System.out.println("Private key (HS_UPDATE_SIGNING_KEY, keep secret):");
                System.out.println(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
                System.out.println("Public key (UpdateSignature.PUBLIC_KEY):");
                System.out.println(Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            }
            case "sign" -> {
                String secret = System.getenv("HS_UPDATE_SIGNING_KEY");
                if (secret == null || secret.isBlank()) fail("HS_UPDATE_SIGNING_KEY is not set.");
                var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(secret.trim())));
                for (int i = 1; i < args.length; i++) {
                    Path file = Path.of(args[i]);
                    if (file.toString().endsWith(".sig")) continue;
                    var signer = Signature.getInstance("Ed25519");
                    signer.initSign(key);
                    signer.update(message(file));
                    Files.writeString(Path.of(file + ".sig"), Base64.getEncoder().encodeToString(signer.sign()) + "\n");
                    System.out.println("Signed " + file.getFileName());
                }
            }
            case "verify" -> {
                if (args.length < 3) usage();
                var key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(args[1].trim())));
                boolean ok = true;
                for (int i = 2; i < args.length; i++) {
                    Path file = Path.of(args[i]);
                    var verifier = Signature.getInstance("Ed25519");
                    verifier.initVerify(key);
                    verifier.update(message(file));
                    boolean valid = verifier.verify(Base64.getDecoder().decode(Files.readString(Path.of(file + ".sig")).trim()));
                    System.out.println((valid ? "OK   " : "BAD  ") + file.getFileName());
                    ok &= valid;
                }
                if (!ok) System.exit(1);
            }
            default -> usage();
        }
    }

    private static byte[] message(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) digest.update(buffer, 0, n);
        }
        String text = "hyperstream-update-v1\n" + file.getFileName() + "\n" + HexFormat.of().formatHex(digest.digest()) + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void usage() {
        fail("Usage: java scripts/SignRelease.java generate | sign FILE... | verify PUBLIC_KEY FILE...");
    }

    private static void fail(String message) {
        System.err.println(message);
        System.exit(2);
    }
}
