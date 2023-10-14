package com.ian4d.confessional.infrastructure;

import org.jetbrains.annotations.NotNull;
import software.amazon.awscdk.BundlingOptions;
import software.amazon.awscdk.BundlingOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.iam.AccessKey;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.User;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.FunctionProps;
import software.amazon.awscdk.services.lambda.LayerVersion;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.eventsources.S3EventSource;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.NotificationKeyFilter;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class InfrastructureStack extends Stack {

    private static final List<String> BUILD_COMMANDS = Arrays.asList(
            "/bin/sh",
            "-c",
            "gradle clean build --info && cp ./build/distributions/asset-input-0.1.0-SNAPSHOT.zip /asset-output/"
    );

    private static final BundlingOptions BUNDLING_OPTIONS = BundlingOptions.builder()
            .command(BUILD_COMMANDS)
            .image(Runtime.JAVA_17.getBundlingImage())
            .user("root")
            .outputType(BundlingOutput.ARCHIVED)
            .build();

    public InfrastructureStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        final Bucket recordingBucket = createRecordingBucket();

        // Create a User that can publish to our recording bucket
        final User twilioUser = createTwilioUser(recordingBucket);

        // Create a user to allow Github to sync objects out of our bucket
        final User githubUser = createGithubUser(recordingBucket);


        // Create a function to handle incoming Twilio requests
        final Function confessorLambda = buildTwilioConfessionHandler();

        // Create a function to convert Twilio WAVs to MP3s for smaller file size
        final Function audioConversionLambda = buildAudioConversionHandler(recordingBucket);

        // Integrate the twilio handler with API Gateway so Twilio can invoke it
        final RestApi restAPI = buildAPIGatewayIntegration(confessorLambda);
    }

    private RestApi buildAPIGatewayIntegration(Function confessorLambda) {
        LambdaIntegration lambdaIntegration = LambdaIntegration.Builder.create(confessorLambda)
                .requestTemplates(
                        Map.of("application/json", "{ 'statusCode': '200' }")
                )
                .proxy(true)
                .build();
        RestApi api = RestApi.Builder.create(this, "ConfessionalAPI")
                .restApiName("Confessional")
                .build();
        api.getRoot().addMethod("POST", lambdaIntegration);
        return api;
    }

    @NotNull
    private Function buildAudioConversionHandler(Bucket recordingBucket) {
        final Function audioConversionLambda = new Function(this, "AudioConversionFunction", FunctionProps.builder()
                .functionName("AudioConversionHandler")
                .runtime(Runtime.JAVA_17)
                .handler("com.ian4d.confessional.handler.AudioConversionHandler::handleRequest")
                .memorySize(1024)
                .code(Code.fromAsset("../converter/", AssetOptions.builder()
                        .bundling(BUNDLING_OPTIONS)
                        .build()))
                .timeout(Duration.minutes(10))
                .layers(Arrays.asList(
                        LayerVersion.fromLayerVersionArn(this, "ffmpeg-layer", "arn:aws:lambda:us-east-1:910367143091:layer:ffmpeg:1")
                ))
                .logRetention(RetentionDays.ONE_WEEK)
                .build());
        recordingBucket.grantReadWrite(audioConversionLambda);

        // When an object is written to our recording bucket ingest the event so we can convert it
        audioConversionLambda.addEventSource(S3EventSource.Builder.create(recordingBucket)
                .events(Arrays.asList(
                        EventType.OBJECT_CREATED
                ))
                .filters(Arrays.asList(
                        NotificationKeyFilter.builder()
                                .prefix("recordings")
                                .build()
                ))
                .build());

        return audioConversionLambda;
    }

    @NotNull
    private Function buildTwilioConfessionHandler() {
        final Function confessorLambda = new Function(this, "ConfessorFunction", FunctionProps.builder()
                .functionName("TwilioConfessionHandler")
                .runtime(Runtime.JAVA_17)
                .handler("com.ian4d.confessional.handler.TwilioHandler::handleRequest")
                .memorySize(1024)
                .code(Code.fromAsset("../twilio/", AssetOptions.builder()
                        .bundling(BUNDLING_OPTIONS)
                        .build()))
                .timeout(Duration.minutes(1))
                .logRetention(RetentionDays.ONE_WEEK)
                .build());

        confessorLambda.addToRolePolicy(PolicyStatement.Builder
                .create()
                .sid("RetrieveTwilioSecrets")
                .actions(Arrays.asList(
                        "secretsmanager:GetResourcePolicy",
                        "secretsmanager:GetSecretValue",
                        "secretsmanager:DescribeSecret",
                        "secretsmanager:ListSecretVersionIds"))
                .effect(Effect.ALLOW)
                .resources(Arrays.asList(
                        "arn:aws:secretsmanager:us-east-1:910367143091:secret:TWILIO*"
                ))
                .build());

        return confessorLambda;
    }

    /**
     * Creates the User that Github will use to pull audio into our Jekyll website.
     *
     * @param recordingBucket The bucket to read from.
     * @return User
     */
    User createGithubUser(Bucket recordingBucket) {
        final User githubUser = buildIAMUser("GithubUser");
        githubUser.addToPolicy(PolicyStatement.Builder.create()
                .sid("AllowReadForGithubActions")
                .actions(Arrays.asList(
                        "s3:ListBucket",
                        "s3:GetObject"
                ))
                .resources(Arrays.asList(recordingBucket.getBucketArn()))
                .effect(Effect.ALLOW)
                .build());
        recordingBucket.grantRead(githubUser);
        return githubUser;
    }

    /**
     * Creates the User that Twilio will assume the role of to write recordings to our S3 bucket.
     *
     * @param recordingBucket The bucket that will be written to.
     * @return User
     */
    User createTwilioUser(Bucket recordingBucket) {
        final User twilioUser = buildIAMUser("TwilioUser");
        twilioUser.addToPolicy(PolicyStatement.Builder.create()
                .sid("UploadUserDenyEverything")
                .notActions(Arrays.asList("*"))
                .resources(Arrays.asList("*"))
                .effect(Effect.DENY)
                .build());
        twilioUser.addToPolicy(PolicyStatement.Builder.create()
                .sid("UploadUserListBucketMultipartUploads")
                .actions(Arrays.asList(
                        "s3:ListBucketMultipartUploads"
                ))
                .resources(Arrays.asList(recordingBucket.getBucketArn()))
                .effect(Effect.ALLOW)
                .build());
        twilioUser.addToPolicy(PolicyStatement.Builder.create()
                .sid("UploadUserAllowPutObjectAndMultipartUpload")
                .actions(Arrays.asList(
                        "s3:PutObject",
                        "s3:AbortMultipartUpload",
                        "s3:ListMultipartUploadParts"
                ))
                .resources(Arrays.asList(
                        String.format("%s/recordings/*", recordingBucket.getBucketArn())
                ))
                .effect(Effect.ALLOW)
                .build());
        return twilioUser;
    }

    /**
     * Creates the bucket that Twilio will write recordings to.
     *
     * @return Bucket
     */
    Bucket createRecordingBucket() {
        // Create a bucket to store recordings from Twilio
        final Bucket recordingBucket =
                Bucket.Builder.create(this, "TwilioRecordingBucket")
                        .bucketName("ian4d-twilio-confessor")
                        .build();
        return recordingBucket;
    }

    /**
     * Builds an IAM User with an access key and secret based on that key
     *
     * @param username Name of the user
     * @return User
     */
    User buildIAMUser(String username) {
        // Create the User
        final User user = User.Builder.create(this, username)
                .userName(username)
                .build();

        // Create an Access Key for the User
        final AccessKey accessKey = AccessKey.Builder.create(this, String.format("%s-access-key", username))
                .user(user)
                .build();

        // Create a Secret for the User
        String secretName = String.format("%s-secret", username);
        Secret.Builder.create(this, secretName)
                .secretName(secretName)
                .secretStringValue(accessKey.getSecretAccessKey())
                .build();
        return user;
    }
}
