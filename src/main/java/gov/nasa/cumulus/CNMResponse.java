package gov.nasa.cumulus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.apache.commons.io.IOUtils;

import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClient;
import com.amazonaws.services.kinesis.model.PutRecordRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.sns.AmazonSNS;
import com.amazonaws.services.sns.AmazonSNSClientBuilder;
import com.amazonaws.services.sns.model.PublishRequest;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import cumulus_message_adapter.message_parser.ITask;
import cumulus_message_adapter.message_parser.MessageAdapterException;
import cumulus_message_adapter.message_parser.MessageParser;


public class CNMResponse implements  ITask, RequestHandler<String, String>{

	public enum ErrorCode {VALIDATION_ERROR, TRANSFER_ERROR, PROCESSING_ERROR};

	public String handleRequest(String input, Context context) {
		MessageParser parser = new MessageParser();
		try
		{
			return parser.RunCumulusTask(input, context, new CNMResponse());
		}
		catch(MessageAdapterException e)
		{
			return e.getMessage();
		}
	}

	public void handleRequestStreams(InputStream inputStream, OutputStream outputStream, Context context) throws IOException, MessageAdapterException {
		MessageParser parser = new MessageParser();

		String input =IOUtils.toString(inputStream, "UTF-8");
		context.getLogger().log(input);
		String output = parser.RunCumulusTask(input, context, new CNMResponse());
		System.out.println("Output: " + output);
		outputStream.write(output.getBytes(Charset.forName("UTF-8")));
	}


	public void handleRequestStreams2(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {

		MessageParser parser = new MessageParser();


			String input =IOUtils.toString(inputStream, "UTF-8");
			context.getLogger().log(input);
			CNMResponse cnmresponse = new CNMResponse();
			try{
				String output = cnmresponse.PerformFunction(input, context);
				System.out.println("Output: " + output);
			outputStream.write(output.getBytes(Charset.forName("UTF-8")));
			}catch(Exception e){
				e.printStackTrace();
			}


	}

	public static JsonObject getResponseObject(String exception) {
		JsonObject response = new JsonObject();

		if(exception == null || new String("").equals(exception) ||  new String("None").equals(exception) ||  new String("\"None\"").equals(exception)){
			//success
			response.addProperty("status", "SUCCESS");
		}else{
			//fail
			response.addProperty("status", "FAILURE");

			//logic for failure types here
			JsonObject workflowException = new JsonParser().parse(exception).getAsJsonObject();

			String error = workflowException.get("Error").getAsString();
			switch(error) {
				case "FileNotFound":
				case "RemoteResourceError":
				case "ConnectionTimeout":
					response.addProperty("errorCode", ErrorCode.TRANSFER_ERROR.toString());
					break;
				case "InvalidChecksum":
				case "UnexpectedFileSize":
					response.addProperty("errorCode", ErrorCode.VALIDATION_ERROR.toString());
					break;
				default:
					response.addProperty("errorCode", ErrorCode.PROCESSING_ERROR.toString());
			}

			String causeString = workflowException.get("Cause").getAsString();
			try {
				JsonObject cause = new JsonParser().parse(causeString).getAsJsonObject();
				response.addProperty("errorMessage", cause.get("errorMessage").getAsString());
			} catch (Exception e) {
				response.addProperty("errorMessage", causeString);
			}
		}
		return response;
	}

	public static String generateOutput(String inputCnm, String exception, JsonObject granule){
		//convert CNM to GranuleObject
		JsonElement jelement = new JsonParser().parse(inputCnm);
		JsonObject inputKey = jelement.getAsJsonObject();

		JsonElement sizeElement = inputKey.get("product").getAsJsonObject().get("files").getAsJsonArray().get(0).getAsJsonObject().get("size");

		inputKey.remove("product");

		JsonObject response = getResponseObject(exception);
		inputKey.add("response", response);

		if (granule != null && response.get("status").getAsString().equals("SUCCESS")) {
			JsonObject ingestionMetadata = new JsonObject();
			ingestionMetadata.addProperty("catalogId", granule.get("cmrConceptId").getAsString());
			ingestionMetadata.addProperty("catalogUrl", granule.get("cmrLink").getAsString());
			response.add("ingestionMetadata", ingestionMetadata);
		}

		TimeZone tz = TimeZone.getTimeZone("UTC");
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
		df.setTimeZone(tz);
		String nowAsISO = df.format(new Date());

		inputKey.addProperty("processCompleteTime", nowAsISO);
		return new Gson().toJson(inputKey);
	}

	/**
	 * @param response The message to send to the kinesis stream
	 * @param region an AWS region, probably us-west-2 or us-east-1
	 * @param topicArn The SNS topic ARN to which the message should be sent
	 */
	public static void sendMessageSNS(String response, String region, String topicArn){
		AmazonSNS snsClient = AmazonSNSClientBuilder.standard().withRegion(region).build();
		final PublishRequest publishRequest = new PublishRequest(topicArn, response);
		/*final PublishResult publishResponse =*/ snsClient.publish(publishRequest);
	}

	/**
	 * @param response The message to send to the kinesis stream
	 * @param region an AWS region, probably us-west-2 or us-east-1
	 * @param streamName - the stream name, not ARN, of the kinesis stream
	 */
	public static void sendMessageKinesis(String response, String region, String streamName) {

        //AWSCredentials credentials = CredentialUtils.getCredentialsProvider().getCredentials();
        AmazonKinesis kinesisClient = new AmazonKinesisClient();
        kinesisClient.setRegion(RegionUtils.getRegion(region));

	    //byte[] bytes = new Gson().toJson(response).getBytes();
	    //we already have the json as a string, so we don't need the above command to re-string it.
        byte[] bytes = response.getBytes();

	    if (bytes == null) {
	        return;
	    }


	    PutRecordRequest putRecord = new PutRecordRequest();
	    putRecord.setStreamName(streamName);
	    putRecord.setPartitionKey("1");
	    putRecord.setData(ByteBuffer.wrap(bytes));
	    kinesisClient.putRecord(putRecord);
	}


        public String getError(JsonObject input, String key){

		String exception = null;
		System.out.println("WorkflowException: " + input.get(key));

		if(input.get(key) != null){
			System.out.println("Step 3.5");
			exception = input.get(key).toString();
		}
		return exception;
	}

	//inputs
	// OriginalCNM
	// CNMResponseStream
	// WorkflowException
	// region
	public String PerformFunction(String input, Context context) throws Exception {

		System.out.println("Processing " + input);

		JsonElement jelement = new JsonParser().parse(input);
		JsonObject inputKey = jelement.getAsJsonObject();


		JsonObject  inputConfig = inputKey.getAsJsonObject("config");
		String cnm = new Gson().toJson(inputConfig.get("OriginalCNM"));
		String exception = getError(inputConfig, "WorkflowException");

		JsonObject granule = inputKey.get("input").getAsJsonObject().get("granules").getAsJsonArray().get(0).getAsJsonObject();

		String output = CNMResponse.generateOutput(cnm,exception, granule);
		String method = inputConfig.get("type").getAsString();
		String region = inputConfig.get("region").getAsString();
		String endpoint = inputConfig.get("response-endpoint").getAsString();

		/*
		 * This needs to be refactored into a factory taking 'type' as an input
		 */
		if(method != null && method.equals("kinesis")){
			CNMResponse.sendMessageKinesis(output, region, endpoint);
		}else if(method != null && method.equals("sns")){
			CNMResponse.sendMessageSNS(output, region, endpoint);
		}

		/* create new object:
		 *
		 * {cnm: output, input:input}
		 *
		 */
		JsonObject bigOutput = new JsonObject();
		bigOutput.add("cnm", new JsonParser().parse(output).getAsJsonObject());
		bigOutput.add("input", new JsonParser().parse(input).getAsJsonObject());

		return new Gson().toJson(bigOutput);
	}
}
