package ca.bc.gov.nrs.wfdm.wfdm_file_index_initializer;

import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.TransformerConfigurationException;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.lambda.AWSLambda;
import com.amazonaws.services.lambda.AWSLambdaAsyncClient;
import com.amazonaws.services.lambda.model.InvokeRequest;
import com.amazonaws.services.lambda.model.InvokeResult;
import com.mashape.unirest.http.exceptions.UnirestException;

import org.json.JSONObject;
import org.xml.sax.SAXException;

/**
 * Processor for the received SQS messages. As messages are placed onto the Queue
 * they'll be pulled by this handler. The message should be a WFDM file Resource,
 * and a message type of "BYTES" or "META". 
 * For "BYTES" messages, this file will then be fetched from WFDM, and pushed onto
 * the clamAV bucket for virus scanning. This process will have a handler that can
 * then trigger the tika parsing.
 * For "META" messages, the Indexer lambda will be triggered directly, with no bytes
 * and that lambda will only update metadata to the opensearch index
 */
public class ProcessSQSMessage implements RequestHandler<SQSEvent, SQSBatchResponse> {
  private static String region = "ca-central-1";
  private static String bucketName = "wfdmclamavstack-wfdmclamavbucket78961613-4r53u9f2ef2v"; // open-search-index-bucket already exists? Use that?
  static final AWSCredentialsProvider credentialsProvider = new DefaultAWSCredentialsProviderChain();

  @Override
  public SQSBatchResponse handleRequest(SQSEvent sqsEvent, Context context) {
    LambdaLogger logger = context.getLogger();
    List<SQSBatchResponse.BatchItemFailure> batchItemFailures = new ArrayList<>();
    String messageBody = "";

    // null check sqsEvents!
    if (sqsEvent == null || sqsEvent.getRecords() == null) {
      logger.log("\nInfo: No messages to handle\nInfo: Close SQS batch");
      return new SQSBatchResponse(batchItemFailures);
    }

    // Iterate the available messages
    for (SQSEvent.SQSMessage message : sqsEvent.getRecords()) {
      try {
        messageBody = message.getBody();
        logger.log("\nInfo: SQS Message Received: " + messageBody);

        JSONObject messageDetails = new JSONObject(messageBody);
        String fileId = messageDetails.getString("fileId");
        String eventType = messageDetails.getString("eventType");

        // Check the event type. If this is a BYTES event, write the bytes
        // otherwise, handle meta only and skip clam scan.
        if (eventType.equalsIgnoreCase("bytes")) {
          String versionNumber = messageDetails.getString("versionNumber");

          String CLIENT_ID = "<CLIENT>";
          String PASSWORD = "<Password>";

          String wfdmToken = GetFileFromWFDMAPI.getAccessToken(CLIENT_ID, PASSWORD);
          if (wfdmToken == null)
            throw new Exception("Could not authorize access for WFDM");

          String fileInfo = GetFileFromWFDMAPI.getFileInformation(wfdmToken, fileId);

          if (fileInfo == null) {
            throw new Exception("File not found!");
          } else {
            JSONObject fileDetailsJson = new JSONObject(fileInfo);
            String mimeType = fileDetailsJson.get("mimeType").toString();

            logger.log("\nInfo: File found on WFDM: " + fileInfo);
            // Update Virus scan metadata
            // Note, current user likely lacks access to update metadata so we'll need to update webade
            boolean metaAdded = GetFileFromWFDMAPI.setVirusScanMetadata(wfdmToken, fileId, versionNumber, fileDetailsJson);
            if (!metaAdded) {
              // We failed to apply the metadata regarding the virus scan status...
              // Should we continue to process the data from this point, or just choke?
            }

            AmazonS3 s3client = AmazonS3ClientBuilder
              .standard()
              .withCredentials(credentialsProvider)
              .withRegion(region)
              .build();

            Bucket clamavBucket = null;
            List<Bucket> buckets = s3client.listBuckets();
            for(Bucket bucket : buckets) {
              if (bucket.getName().equalsIgnoreCase(bucketName)) {
                clamavBucket = bucket;
              }
            }

            if(clamavBucket == null) {
              throw new Exception("S3 Bucket " + bucketName + " does not exist.");
            }

            BufferedInputStream stream = GetFileFromWFDMAPI.getFileStream(wfdmToken, fileId, versionNumber);

            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentType(mimeType);
            meta.setContentLength(Long.parseLong(fileDetailsJson.get("fileSize").toString()));
            meta.addUserMetadata("title", fileId + "-" + versionNumber);
            s3client.putObject(new PutObjectRequest(clamavBucket.getName(), fileDetailsJson.get("fileId").toString() + "-" + versionNumber, stream, meta));
          }
        } else {
          // Meta only update, so fire a message to the Indexer Lambda
          AWSLambda client = AWSLambdaAsyncClient.builder().withRegion(region).build();

          InvokeRequest request = new InvokeRequest();
          request.withFunctionName("wfdm-open-search").withPayload(messageBody);
          InvokeResult invoke = client.invoke(request);
        }
      } catch (UnirestException | TransformerConfigurationException | SAXException e) {
        logger.log("\nError: Failure to recieve file from WFDM: " + e.getLocalizedMessage());
        batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      } catch (Exception ex) {
        logger.log("\nUnhandled Error: " + ex.getLocalizedMessage());
        batchItemFailures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
      } finally {
        // Cleanup
        logger.log("\nInfo: Finalizing processing...");
      }
    }

    logger.log("\nInfo: Close SQS batch");
    return new SQSBatchResponse(batchItemFailures);
  }
}
