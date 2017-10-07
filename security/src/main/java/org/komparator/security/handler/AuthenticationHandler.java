package org.komparator.security.handler;


import pt.ulisboa.tecnico.sdis.ws.cli.CAClient;

import java.io.*;
import java.security.cert.CertificateException;
import javax.xml.bind.DatatypeConverter;
import javax.xml.namespace.QName;
import javax.xml.soap.*;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.ws.handler.MessageContext;
import javax.xml.ws.handler.soap.SOAPHandler;
import javax.xml.ws.handler.soap.SOAPMessageContext;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;



public class AuthenticationHandler implements SOAPHandler<SOAPMessageContext> {
    private static final String PROPERTIES_FILE = "auth.properties";
    private static Properties PROPS;
    private static String JKSPASSWORD;
    private static String PRIVKEYPASS;
    private static String X_NAME;
    private static String JKS_PATH;
    private static KeyStore keyStore;
    private Map<String, Set<UUID>> invalidUUIDs = Collections.synchronizedMap(new HashMap<>());



    private void init() {
        PROPS = new Properties();
        try {
            PROPS.load(getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE));
        } catch (IOException e) {
            final String msg = String.format("Could not load properties file {}", PROPERTIES_FILE);
            System.out.println(msg);
        }
        X_NAME = PROPS.getProperty("X.NAME");
        JKS_PATH = PROPS.getProperty("JKS.PATH");
        JKSPASSWORD = PROPS.getProperty("JKS.PASSWORD");
        PRIVKEYPASS = PROPS.getProperty("JKS.PRIVKEY");
        try {
            keyStore = loadKeyStore();
        } catch (KeyStoreException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    @Override
    public Set<QName> getHeaders() {
        return null;
    }

    @Override
    public boolean handleMessage(SOAPMessageContext smc) {
        if (X_NAME == null) {
            this.init();
        }

        Boolean outboundElement = (Boolean) smc.get(MessageContext.MESSAGE_OUTBOUND_PROPERTY);

        try {
            if (outboundElement.booleanValue()) {
                return handleOutBound(smc);
            } else {
                return handleInBound(smc);
            }
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw new RuntimeException(e.getMessage());
            } else {
                System.out.print("Caught exception in handleMessage: ");
                System.out.println(e);
                System.out.println("Continue normal processing...");
            }
        }

        return true;
    }


    @Override
    public boolean handleFault(SOAPMessageContext context) {
        return false;
    }

    @Override
    public void close(MessageContext context) {

    }

    private boolean handleOutBound(SOAPMessageContext smc) throws SOAPException {
        System.out.println("Writing header in outbound SOAP message...");

        // get SOAP envelope
        SOAPMessage msg = smc.getMessage();
        SOAPPart sp = msg.getSOAPPart();
        SOAPEnvelope se = sp.getEnvelope();

        System.out.println("Generating TimeStamp");

        //Make the created date
        String createdDate = OffsetDateTime.now(ZoneId.of("UTC")).toString();

        //Make UUID for messages
        UUID uuid = UUID.randomUUID();
        String uuidString = uuid.toString();


        // initialize the message digest
        byte [] signedDigest = null;

        // add header
        SOAPHeader sh = se.getHeader();
        if (sh == null)
            sh = se.addHeader();

        System.out.println("Digitally signing the necessary information");
        try {
            signedDigest = makeDigitalSignature(getBytesToSign(se, createdDate, uuidString).toByteArray());
        } catch (Exception e) {
            e.printStackTrace();
        }

        // add header element (name, namespace prefix, namespace)
        Name name = se.createName("Security", "auth", "http://security.komparator.pt");
        SOAPHeaderElement element = sh.addHeaderElement(name);

        SOAPElement messageDigest = element.addChildElement("MessageDigest", "auth");
        messageDigest.addTextNode(DatatypeConverter.printBase64Binary(signedDigest));

        SOAPElement senderName = element.addChildElement("SenderName", "auth");
        senderName.addTextNode(X_NAME);

        SOAPElement dateCreated = element.addChildElement("CreatedDate", "auth");
        dateCreated.addTextNode(createdDate);

        SOAPElement UniqueID = element.addChildElement("UUID", "auth");
        UniqueID.addTextNode(uuidString);

        return true;

    }

    private boolean handleInBound(SOAPMessageContext smc) throws Exception {
        System.out.println("Reading header in inbound SOAP message...");
        OffsetDateTime dateTimeReceived = OffsetDateTime.now();
        // get SOAP envelope header
        SOAPMessage msg = smc.getMessage();
        SOAPPart sp = msg.getSOAPPart();
        SOAPEnvelope se = sp.getEnvelope();
        SOAPHeader sh = se.getHeader();
        sh.detachNode();
        // check header
        if (sh == null) {
            System.out.println("Header not found.");
            return false;
        }

        // get first header element
        Name name = se.createName("Security", "auth", "http://security.komparator.pt");
        Iterator it = sh.getChildElements(name);
        // check header element
        if (!it.hasNext()) {
            System.out.println("Header element not found.");
            return false;
        }
        SOAPElement Security = (SOAPElement) it.next();
        it = Security.getChildElements();

        SOAPElement messageDigest = (SOAPElement) it.next();
        SOAPElement senderName = (SOAPElement) it.next();
        SOAPElement createdDate = (SOAPElement) it.next();
        SOAPElement UniqueID = (SOAPElement) it.next();

        String dateTimeSent = createdDate.getValue();

        if (!validDate(dateTimeSent, dateTimeReceived)) {
            throw new RuntimeException("There is a security issue.");
        }

        String uuidString = UniqueID.getValue();

        if (!validUUID(uuidString, senderName.getValue())) {
            System.out.println("UUID ISSUE");
            throw new RuntimeException("There is a security issue.");
        }

        if(verifyDigitalSignature(DatatypeConverter.parseBase64Binary(messageDigest.getValue()),
                    getBytesToSign(se, dateTimeSent, uuidString).toByteArray(), senderName.getValue())){
            return true;
        } else {
            throw new RuntimeException("There is a security issue.");
        }

    }

    private boolean validDate(String date, OffsetDateTime dateTimeReceived) {
        long acceptedInterval = 3;
        OffsetDateTime dateTimeSent = OffsetDateTime.parse(date);
        return dateTimeSent.isAfter(dateTimeReceived.minusSeconds(acceptedInterval)) &&
                dateTimeSent.isBefore(dateTimeReceived.plusSeconds(acceptedInterval));
    }

    private boolean validUUID(String uuidString, String senderAlias) {
        UUID uuid = UUID.fromString(uuidString);
        if (invalidUUIDs.containsKey(senderAlias)) {
            return invalidUUIDs.get(senderAlias).add(uuid);
        } else {
            Set<UUID> Synset = Collections.synchronizedSet(new HashSet<>());
            invalidUUIDs.put(senderAlias, Synset);
            return invalidUUIDs.get(senderAlias).add(uuid);
        }
    }

    // make the byte array for the digest
    private static ByteArrayOutputStream getBytesToSign(SOAPEnvelope se, String createdDate, String uuid) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            byte[] createdDateBytes = createdDate.getBytes("UTF-8");
            byte[] uuidBytes = uuid.getBytes("UTF-8");
            baos.write(SOAPElementToByteArray(se.getBody()));
            baos.write(createdDateBytes);
            baos.write(uuidBytes);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (TransformerException e) {
            e.printStackTrace();
        } catch (SOAPException e) {
            e.printStackTrace();
        }
        return baos;
    }

    private static byte[] SOAPElementToByteArray(SOAPElement elem) throws TransformerException, UnsupportedEncodingException {

        DOMSource source = new DOMSource(elem);
        StringWriter stringResult = new StringWriter();
        TransformerFactory.newInstance().newTransformer().transform(source, new StreamResult(stringResult));
        String message = stringResult.toString();

        return DatatypeConverter.parseBase64Binary(message);
    }


    /** auxiliary method to get PrivateKey from jks*/
    private static PrivateKey getPrivateKey() throws Exception {
        PrivateKey key = (PrivateKey)keyStore.getKey(X_NAME.toLowerCase(), PRIVKEYPASS.toCharArray());

        return key;
    }

    /** auxiliary method to get PublicKey from jks*/
    private static PublicKey getPublicKey(String alias) throws Exception {
        PublicKey key = null;
        try {
            Certificate cert = keyStore.getCertificate(alias.toLowerCase());
            if (cert != null) {
                key = cert.getPublicKey();
            } else {
            	CAClient caPort = new CAClient("http://sec.sd.rnl.tecnico.ulisboa.pt:8081/ca?WSDL");
                byte[] certBytes = caPort.getCertificate(alias.toLowerCase()).getBytes();
                cert = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes));
                if(!trustedCertificate(cert)) {
                    throw new RuntimeException("Security error");
                } else {
                    keyStore.setCertificateEntry(alias.toLowerCase(), cert);
                    key = cert.getPublicKey();
                }
            }

        } catch (KeyStoreException e) {
            e.printStackTrace();
        }

        return key;
    }

    public static boolean trustedCertificate(Certificate certificate) {
        String TrustedAlias = "ca";
        try {
            certificate.verify(keyStore.getCertificate(TrustedAlias).getPublicKey());
        } catch (InvalidKeyException | KeyStoreException | CertificateException | NoSuchAlgorithmException
                | NoSuchProviderException | SignatureException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private static KeyStore loadKeyStore() throws KeyStoreException, IOException {
        KeyStore ks = KeyStore.getInstance("JKS");
        java.io.FileInputStream fis = null;
        try {
            fis = new java.io.FileInputStream(JKS_PATH);
            ks.load(fis, JKSPASSWORD.toCharArray());
        } catch (java.security.cert.CertificateException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fis != null) {
                fis.close();
            }
        }
        return ks;
    }

    /** auxiliary method to calculate digest from text and cipher it */
    private static byte[] makeDigitalSignature(byte[] bytes) throws Exception {

        // get a signature object using the SHA-256 and RSA combo
        // and sign the plaintext with the private key
        PrivateKey key = getPrivateKey();
        Signature sig = Signature.getInstance("SHA256WithRSA");
        sig.initSign(key);
        sig.update(bytes);
        byte[] signature = sig.sign();

        return signature;
    }

    /**
     * auxiliary method to calculate new digest from text and compare it to the
     * to deciphered digest
     */
    private static boolean verifyDigitalSignature(byte[] cipherDigest, byte[] bytes, String senderAlias) throws Exception {

        // verify the signature with the public key
        Signature sig = Signature.getInstance("SHA256WithRSA");
        sig.initVerify(getPublicKey(senderAlias));
        sig.update(bytes);
        try {
            return sig.verify(cipherDigest);
        } catch (SignatureException se) {
            System.err.println("Caught exception while verifying " + se);
            return false;
        }
    }

}