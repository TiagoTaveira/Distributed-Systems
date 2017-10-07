package org.komparator.security;

import java.io.*;
import java.security.*;
import javax.crypto.*;
import java.util.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;

import org.junit.*;
import static org.junit.Assert.*;

import org.komparator.security.CryptoUtil;

public class CryptoUtilTest {


    private static String certificateResourcePath= "example.cer";
    private static String keyStoreResourcePath = "example.jks";
    private static String keyAlias = "example";
    private static String keyStorePassword ="1nsecure";
    private static String keyPassword = "ins3cur3";

    static PublicKey Pukey;
    static PrivateKey Prikey;
    // static members

    // one-time initialization and clean-up
    @BeforeClass
    public static void oneTimeSetUp() {
        // runs once before all tests in the suite
        try {
			Certificate cert = CryptoUtil.getX509CertificateFromResource(certificateResourcePath);
			Pukey = CryptoUtil.getPublicKeyFromCertificate(cert);
			Prikey = CryptoUtil.getPrivateKeyFromKeyStoreResource(keyStoreResourcePath, keyStorePassword.toCharArray(), keyAlias, keyPassword.toCharArray());
		} catch (CertificateException | IOException e) {
			e.printStackTrace();
		} catch (UnrecoverableKeyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (KeyStoreException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }

    @AfterClass
    public static void oneTimeTearDown() {
        // runs once after all tests in the suite
    }

    // members

    // initialization and clean-up for each test
    @Before
    public void setUp() {
        // runs before each test
    }

    @After
    public void tearDown() {
        // runs after each test
    }

    // tests
    @Test
    public void test() {
        String inpu = "Ai vou chumbar";
        byte[] input = inpu.getBytes();
        try {
			byte[] ciphered = CryptoUtil.encryptWithPubKey(input, Pukey);
			byte[] deciphered = CryptoUtil.decryptWithPrivKey(ciphered, Prikey);
			
			Assert.assertEquals(inpu, new String(deciphered, "UTF-8"));
		} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException | NoSuchAlgorithmException
				| NoSuchPaddingException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
   	
    	// do something ...

        // assertEquals(expected, actual);
        // if the assert fails, the test fails
    }

}
