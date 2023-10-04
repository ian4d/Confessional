package com.ian4d.confessional.handler;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.S3Event;
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bramp.ffmpeg.FFmpeg;
import net.bramp.ffmpeg.FFmpegExecutor;
import net.bramp.ffmpeg.FFprobe;
import net.bramp.ffmpeg.builder.FFmpegBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.time.Duration;

public class AudioConversionHandler implements RequestHandler<S3Event, Void> {

    private static final Logger logger = LogManager.getLogger();
    private static final Gson gson = new GsonBuilder().create();

    @Override
    public Void handleRequest(S3Event input, Context context) {

        logger.info("s3Event: {}", gson.toJson(input));

        input.getRecords().stream()
                .filter(record -> "ObjectCreated:Put".equals(record.getEventName()))
                .map(S3EventNotification.S3EventNotificationRecord::getS3)
                .forEach(this::processEntity);
        return null;
    }

    void processEntity(S3EventNotification.S3Entity entity) {
        String bucketName = entity.getBucket().getName();
        String objectKey = entity.getObject().getKey();


        logger.info("Bucket: {}", bucketName);
        logger.info("Key: {}", objectKey);

        try {

            File inputFile = new File(String.format("/tmp/input/%s", objectKey));
            inputFile.getParentFile().mkdirs();
            File outputFile = new File(String.format("/tmp/output/%s", objectKey));
            outputFile.getParentFile().mkdirs();

            logger.info("Desired input file: {}", inputFile.toString());
            logger.info("Desired output file: {}", outputFile.toString());

            S3Client s3 = S3Client.builder()
                    .region(Region.US_EAST_1)
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();

            logger.info("S3 Client: {}", s3);

            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(objectKey)
                    .build();

            logger.info("Get object request: {}", getObjectRequest.toString());

            ResponseBytes<GetObjectResponse> responseBytes = s3.getObjectAsBytes(getObjectRequest);
            byte[] objectData = responseBytes.asByteArray();
            OutputStream os = new FileOutputStream(inputFile);
            os.write(objectData);
            os.close();

            logger.info("Before conversion");
            logger.info("Input file exists? {}", inputFile.exists());
            logger.info("Output file exists? {}", outputFile.exists());

            FFmpeg ffMpeg = new FFmpeg("/opt/bin/ffmpeg");
            FFprobe ffProbe = new FFprobe("/opt/bin/ffprobe");

            // Build url to load in file
            String s3Url = String.format("https://%s.amazonaws.com/%s", bucketName, objectKey);
            logger.info("S3 URL: {}", s3Url);

            // Create output builder
            FFmpegBuilder outputBuilder = new FFmpegBuilder()
                    .setInput(inputFile.getAbsolutePath())
                    .overrideOutputFiles(true)
                    .addOutput(outputFile.getAbsolutePath())
                    .setFormat("mp3")
                    .disableVideo()
                    .setAudioChannels(1)
                    .setAudioCodec("mp3")
                    .setAudioSampleRate(48_000)
                    .setAudioBitRate(32768)
                    .done();

            FFmpegExecutor executor = new FFmpegExecutor(ffMpeg, ffProbe);
            executor.createJob(outputBuilder).run();

            logger.info("After conversion");
            logger.info("Input file exists? {}", inputFile.exists());
            logger.info("Output file exists? {}", outputFile.exists());


            PutObjectRequest objectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(String.format("mp3/%s.mp3", System.currentTimeMillis()))
                    .contentType("audio/mpeg")
                    .build();
            logger.info("Put request: {}", objectRequest.toString());

            PutObjectResponse putResponse = s3.putObject(objectRequest, RequestBody.fromFile(outputFile));
            logger.info("Put response: {}", putResponse.toString());


//                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
//                connection.setDoOutput(true);
//                connection.setRequestProperty("Content-Type", "audio/mpeg");
//                connection.setRequestMethod("PUT");
//                OutputStreamWriter out = new OutputStreamWriter(connection.getOutputStream());
//
//
//                out.write("This text was uploaded as an object by using a presigned URL.");
//                out.close();
//
//                connection.getResponseCode();
//                System.out.println("HTTP response code is " + connection.getResponseCode());
        } catch (S3Exception e) {
            throw new RuntimeException(e);
        } catch (ProtocolException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    PresignedPutObjectRequest generatePresignPutObjectRequest(String bucketName, String objectKey, S3Presigner presigner) {
        // Put object request
        PutObjectRequest objectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType("audio/mpeg")
                .build();

        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(10))
                .putObjectRequest(objectRequest)
                .build();

        PresignedPutObjectRequest presignedRequest = presigner.presignPutObject(presignRequest);
        String myURL = presignedRequest.url().toString();
        logger.info("Presigned URL to upload a file to: " + myURL);
        logger.info("Which HTTP method needs to be used when uploading a file: " + presignedRequest.httpRequest().method());
        return presignedRequest;
    }

}
