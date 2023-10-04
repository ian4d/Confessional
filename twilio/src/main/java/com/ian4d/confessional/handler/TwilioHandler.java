/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package com.ian4d.confessional.handler;


import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.twilio.Twilio;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Hangup;
import com.twilio.twiml.voice.Record;
import com.twilio.twiml.voice.Say;

import java.util.Collections;


public class TwilioHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    public TwilioHandler() {
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent input, Context context) {
        // Use <Say> to give the caller some instructions
        Say instructions = new Say.Builder("Hello. Please leave a message after the beep.").build();


        int secondsOfSilenceToEndCall = 0;
        String characterToEndCall = "#";
        int maxLengthToRecordInSeconds = 120;
        boolean playBeep = true;

        Twilio.init(System.getenv("TWILIO_ACCOUNT_SID"), System.getenv("TWILIO_AUTH_TOKEN"));

        // Use <Record> to record the caller's message
        Record record = new Record.Builder()
                .timeout(secondsOfSilenceToEndCall)
                .finishOnKey(characterToEndCall)
                .maxLength(maxLengthToRecordInSeconds)
                .playBeep(playBeep)
                .build();

        // End the call with <Hangup>
        Hangup hangup = new Hangup.Builder().build();

        // Create a TwiML builder object
        VoiceResponse twiml = new VoiceResponse.Builder()
                .say(instructions)
                .record(record)
                .hangup(hangup)
                .build();

        APIGatewayV2HTTPResponse apiResponse = APIGatewayV2HTTPResponse.builder()
                .withBody(twiml.toXml())
                .withStatusCode(200)
                .withHeaders(Collections.singletonMap(
                        "content-type", "text/xml"
                ))
                .build();
        return apiResponse;
    }
}
