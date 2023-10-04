package com.ian4d.confessional.infrastructure;

import software.amazon.awscdk.*;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.iam.AccessKey;
import software.amazon.awscdk.services.iam.Effect;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.User;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.*;
import software.amazon.awscdk.services.lambda.eventsources.S3EventSource;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.EventType;
import software.amazon.awscdk.services.s3.NotificationKeyFilter;
import software.amazon.awscdk.services.s3.assets.AssetOptions;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InfrastructureStack extends Stack {
    public InfrastructureStack(final Construct scope, final String id) {
        this(scope, id, null);
    }

    public InfrastructureStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        // Create a bucket to store recordings from Twilio
        final Bucket recordingBucket =
                Bucket.Builder.create(this, "TwilioRecordingBucket")
                        .bucketName("ian4d-twilio-confessor")
                        .build();

        // Create a User that can publish to our recording bucket
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

        // Create a user to allow Github to sync objects out of our bucket
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

        // Commands to build lambda within the container during bundling
        List<String> buildCommands = Arrays.asList(
                "/bin/sh",
                "-c",
                "gradle clean build --info && cp ./build/distributions/asset-input-0.1.0-SNAPSHOT.zip /asset-output/"
        );

        // Bundling options to build lambda functions
        BundlingOptions.Builder bundlingOptions = BundlingOptions.builder()
                .command(buildCommands)
                .image(Runtime.JAVA_17.getBundlingImage())
                .user("root")
                .outputType(BundlingOutput.ARCHIVED);

        // TODO: Replace with real credentials
        Map<String, String> environmentMap = new HashMap<>();
        environmentMap.put("TWILIO_ACCOUNT_SID", "AC3aa4f2eda849d6e6fb7fc8b5ec1ddc4e");
        environmentMap.put("TWILIO_AUTH_TOKEN", "6c5e108d4499b9dfac6fd250a687a515");

        // Create a function to handle incoming Twilio requests
        final Function confessorLambda = new Function(this, "ConfessorFunction", FunctionProps.builder()
                .functionName("TwilioConfessionHandler")
                .runtime(Runtime.JAVA_17)
                .handler("com.ian4d.confessional.handler.TwilioHandler::handleRequest")
                .memorySize(1024)
                .code(Code.fromAsset("../twilio/", AssetOptions.builder()
                        .bundling(bundlingOptions.build())
                        .build()))
                .timeout(Duration.minutes(1))
                .logRetention(RetentionDays.ONE_WEEK)
                .environment(environmentMap)
                .build());

        // Create a function to convert Twilio WAVs to MP3s for smaller file size
        final Function audioConversionLambda = new Function(this, "AudioConversionFunction", FunctionProps.builder()
                .functionName("AudioConversionHandler")
                .runtime(Runtime.JAVA_17)
                .handler("com.ian4d.confessional.handler.AudioConversionHandler::handleRequest")
                .memorySize(1024)
                .code(Code.fromAsset("../converter/", AssetOptions.builder()
                        .bundling(bundlingOptions.build())
                        .build()))
                .timeout(Duration.minutes(10))
                .layers(Arrays.asList(
                        LayerVersion.fromLayerVersionArn(this, "ffmpeg-layer", "arn:aws:lambda:us-east-1:910367143091:layer:ffmpeg:1")
                ))
                .logRetention(RetentionDays.ONE_WEEK)
                .environment(environmentMap)
                .build());
        recordingBucket.grantReadWrite(audioConversionLambda);

        // Invoke the audio converter every time a new object is put into the recordings bucket
        // This function is going to use FFMPEG to convert the recordings to MP3s
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

        // Integrate the twilio handler with API Gateway so Twilio can invoke it
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
        Secret.Builder.create(this, String.format("%s-secret", username))
                .secretStringValue(accessKey.getSecretAccessKey())
                .build();
        return user;
    }
}
