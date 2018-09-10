package com.nettgryppa.SlackLatexRenderer;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
import javax.swing.JLabel;

import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.ExpectedAttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaClientBuilder;
import com.amazonaws.services.lambda.model.InvocationType;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class LatexRenderingHandler implements RequestHandler<Map, ChallengeResponse> {
    private static final Logger LOG = LogManager.getLogger(LatexRenderingHandler.class);
    private static final String VERIFICATION_TOKEN = System.getenv("SLACK_TOKEN");
    private static final String OAUTH_TOKEN = System.getenv("OAUTH_TOKEN");
    private static final String S3_REGION = getEnvDefault("S3_REGION", "AWS_REGION");
    private static final String LOCK_TABLE = System.getenv("LOCK_TABLE");
    private static final AmazonS3 s3 = AmazonS3ClientBuilder.standard().withRegion(S3_REGION).build();
    private static final AmazonDynamoDB ddb = AmazonDynamoDBClientBuilder.standard().withRegion(S3_REGION).build();
    private static final String BUCKET = System.getenv("BUCKET");
    private static final ExecutorService THREAD_POOL = Executors.newFixedThreadPool(10);
    private static final Pattern MATH_PATTERN = Pattern.compile("\\$\\$([^$]+)\\$\\$");
    private static final String FUNCTION_NAME = System.getenv("AWS_LAMBDA_FUNCTION_NAME");

    private static String getEnvDefault(String ... envName) {
        for (final String name : envName) {
            final String result = System.getenv(name);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    @Override
    public ChallengeResponse handleRequest(Map input, Context context) {
        ObjectMapper mapper = new ObjectMapper();
        System.out.println("RawInput: " + input);
        try {
            if (input.containsKey("body")) {
                final String json = (String) input.get("body");
                input = mapper.readValue(json, Map.class);
            }
            final String token = (String) input.get("token");
            if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                            VERIFICATION_TOKEN.getBytes(StandardCharsets.UTF_8))) {
                return new ChallengeResponse("BAD TOKEN");
            }
            final String type = (String) input.get("type");

            switch (type) {
                case "url_verification":
                    return new ChallengeResponse((String) input.get("challenge"));
                case "event_callback":
                    Map event = (Map) input.get("event");

                    if (event.containsKey("hidden")) {
                        return new ChallengeResponse("hidden");
                    }
                    if (event.containsKey("subtype")) {
                        return new ChallengeResponse("subtype");
                    }
                    final String text = (String) event.get("text");
                    if (!MATH_PATTERN.matcher(text).find()) {
                        return new ChallengeResponse("NO_MATH");
                    }
                    AWSLambda lambda = AWSLambdaClientBuilder.standard().withRegion("us-east-1").build();
                    InvokeRequest lambdaRequest = new InvokeRequest();
                    lambdaRequest.setInvocationType(InvocationType.Event);
                    lambdaRequest.setFunctionName(FUNCTION_NAME);
                    input.put("type", "asyncRender");
                    lambdaRequest.setPayload(mapper.writeValueAsString(input));
                    lambda.invoke(lambdaRequest);
                    return new ChallengeResponse("ASYNC");
                case "asyncRender":
                    Map event2 = (Map) input.get("event");

                    if (event2.containsKey("hidden")) {
                        return new ChallengeResponse("hidden");
                    }
                    if (event2.containsKey("subtype")) {
                        return new ChallengeResponse("subtype");
                    }
                    final String eventId = (String) input.get("event_id");
                    return new ChallengeResponse(parseMessage(eventId, event2));
                default:
                    return new ChallengeResponse("UNKNOWN");
            }
        } catch (Exception e) {
            e.printStackTrace();
            LOG.error("Unknown error", e);
            return new ChallengeResponse("ERROR");
        }
    }

    private String parseMessage(final String eventId, Map tree) throws ClientProtocolException, IOException {
        final String myId = UUID.randomUUID().toString();
        final String channel = (String) tree.get("channel");
        final String user = (String) tree.get("user");
        final String text = (String) tree.get("text");
        final Matcher matcher = MATH_PATTERN.matcher(text);
        // List<String> formulae = new ArrayList<>();
        Map<String, String> hashes = new HashMap<>();
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
        LinkedHashMap<String, Future<Boolean>> images = new LinkedHashMap<>();
        boolean lockClaimed = false;
        while (matcher.find()) {
            if (!lockClaimed && !claimLock(myId, eventId)) {
                return "DEDUPE";
            } else {
                lockClaimed = true;
            }
            final String formula = matcher.group(1);
            // formulae.add(formula);
            final String hash = Hex.encodeHexString(sha256.digest(formula.getBytes(StandardCharsets.UTF_8)))
                            .toLowerCase();
            hashes.put(hash, formula);
            images.put(hash, THREAD_POOL.submit(() -> {
                return renderMath(formula, hash);
            }));
        }
        List<LinkedHashMap> attachments = new ArrayList<>();
        for (Map.Entry<String, Future<Boolean>> e : images.entrySet()) {
            try {
                final boolean success = e.getValue().get();
                if (success) {
                    final String hash = e.getKey();
                    attachments.add(buildAttachment(hashes.get(hash), hash));
                }
            } catch (Exception ex) {
                LOG.error("Unknown error", ex);
            }
        }
        if (attachments.isEmpty()) {
            return "";
        }
        // LOG.info(buildMessage(channel, attachments));
        sendMessage(buildMessage(channel, attachments));
        return "SUCCESS";
    }

    private static final boolean renderMath(final String strFormula, final String hash) {
        try {
            final String fileName = hash + ".png";

            // TODO: Double check to ensure this works
            if (s3.doesObjectExist(BUCKET, fileName)) {
                return true;
            }
            TeXFormula formula = new TeXFormula(strFormula);
            TeXIcon icon = formula.new TeXIconBuilder().setStyle(TeXConstants.STYLE_DISPLAY).setSize(20).build();
            icon.setInsets(new Insets(5, 5, 5, 5));

            BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                            BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            g2.setColor(Color.white);
            g2.fillRect(0, 0, icon.getIconWidth(), icon.getIconHeight());
            JLabel jl = new JLabel();
            jl.setForeground(new Color(0, 0, 0));
            icon.paintIcon(jl, g2, 0, 0);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            byte[] rawImage = baos.toByteArray();

            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(rawImage.length);
            metadata.setContentType("image/png");

            PutObjectRequest req = new PutObjectRequest(BUCKET, fileName, new ByteArrayInputStream(rawImage), metadata);
            req.setCannedAcl(CannedAccessControlList.PublicRead);
            s3.putObject(req);
            return true;
        } catch (Exception ex) {
            LOG.error("Unknown rendering error", ex);
            return false;
        }
    }

    private static LinkedHashMap buildAttachment(String formula, final String hash) {
        LinkedHashMap result = new LinkedHashMap();
        result.put("fallback", formula);
        result.put("image_url", s3.getUrl(BUCKET, hash + ".png"));
        return result;
    }

    private static String buildMessage(String channel, List<?> attachments) throws JsonProcessingException {
        LinkedHashMap result = new LinkedHashMap();
        result.put("channel", channel);
        result.put("attachments", attachments);
        final ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(result);
    }

    private static void sendMessage(String json) throws ClientProtocolException, IOException {
        HttpClient client = HttpClients.createDefault();
        HttpPost post = new HttpPost("https://slack.com/api/chat.postMessage");
        post.setEntity(new StringEntity(json));
        post.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        post.setHeader("Authorization", "Bearer " + OAUTH_TOKEN);
        HttpResponse response = client.execute(post);
        InputStream content = response.getEntity().getContent();
        System.out.println("SLACK Conent: " + IOUtils.toString(content));
    }

    private static boolean claimLock(final String clientId, final String eventId) {
        try {
        ddb.putItem(new PutItemRequest()
            .withTableName(LOCK_TABLE)
            .addItemEntry("EventId", new AttributeValue().withS(eventId))
            .addItemEntry("ClientId", new AttributeValue().withS(clientId))
            .addItemEntry("Expiration", new AttributeValue().withN(Long.toString((System.currentTimeMillis() + 300_000) / 1000)))
            .addExpectedEntry("EventId", new ExpectedAttributeValue().withExists(false)));
        } catch (final ConditionalCheckFailedException ex) {
            return false;
        }
        return true;
    }
}
