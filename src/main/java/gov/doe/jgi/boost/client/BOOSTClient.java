package gov.doe.jgi.boost.client;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.json.JSONObject;

import gov.doe.jgi.boost.commons.FileFormat;
import gov.doe.jgi.boost.commons.SequenceType;
import gov.doe.jgi.boost.commons.Strategy;
import gov.doe.jgi.boost.commons.Vendor;
import gov.doe.jgi.boost.exception.BOOSTClientException;

/**
 * 
 * @author Ernst Oberortner
 */
public class BOOSTClient {

	private Client client;
	private String token;
	
	// local dev
//	private static final String SPL_REST_URL = "http://localhost:8080/spl-web/rest";
	
	private static final String SPL_REST_URL = "https://boost.jgi.doe.gov/rest/";
	
	/**
	 * default no-args constructor 
	 */
	public BOOSTClient() {
		this.client = ClientBuilder.newClient();
		this.token = null;
	}

	/**
	 * The login() method authenticates a SPL user with username and password.
	 * 
	 * @param username ... the username
	 * @param password ... the password
	 * 
	 * @throws BOOSTClientException
	 */
	public void login(final String username, final String password) 
			throws BOOSTClientException {

		// build the URL of the SPL REST authentication resource
		WebTarget webTarget = client.target(SPL_REST_URL).path("auth").path("login");
		Invocation.Builder invocationBuilder =  webTarget.request(MediaType.APPLICATION_JSON);
		
		// build the request data
		JSONObject jsonRequest = new JSONObject();
		jsonRequest.put("username", username);
		jsonRequest.put("password", password);

		// use POST to submit the request
		Response response = null;
		try {
			response = invocationBuilder.post(
					Entity.entity(jsonRequest.toString(), MediaType.APPLICATION_JSON));
		} catch(Exception e) {
			throw new BOOSTClientException(e.getLocalizedMessage());
		}

		// handle the response
		if(null != response) {

			switch(response.getStatus()) {
			case 200:	// OK
				// the response must have a token (for an authenticated user)
				this.token = this.parseToken(response.readEntity(String.class));
				
				if(null == token) {
					// the response does not have a token
					throw new BOOSTClientException("Invalid username/password!");
				} 
				
				return;
			default:
				// for every response code other then 200, 
				// we throw an exception
				throw new BOOSTClientException(response.getStatus() + ": " + response.getStatusInfo());
			}
		}

	}
	
	private String parseToken(final String response) {
		JSONObject jsonResponse = new JSONObject(response);
		if(jsonResponse.has("token")) {
			return jsonResponse.getString("token");
		}
		return null;
	}
	
	/**
	 * 
	 * @param filenameSequences
	 * @param type
	 * @param bCodingSequences
	 * @param strategy
	 * @param filenameCodonUsageTable
	 */
	public void reverseTranslate(
			final String filenameSequences, 
			Strategy strategy, final String filenameCodonUsageTable,
			final FileFormat outputFormat)
				throws BOOSTClientException, IOException {

		Response response = 
				this.invokeJuggler(filenameSequences, SequenceType.PROTEIN, true,
						strategy, filenameCodonUsageTable, outputFormat);

		handleResponse(response);
	}
	
	/**
	 * 
	 * @param filenameSequences
	 * @param strategy
	 * @param filenameCodonUsageTable
	 * @param outputFormat
	 * @throws BOOSTClientException
	 * @throws IOException
	 */
	public void codonJuggle(
			final String filenameSequences, boolean bAutoAnnotate, 
			Strategy strategy, final String filenameCodonUsageTable,
			final FileFormat outputFormat)
				throws BOOSTClientException, IOException {

		Response response = 
				this.invokeJuggler(
						filenameSequences, SequenceType.DNA, bAutoAnnotate,
						strategy, filenameCodonUsageTable, 
						outputFormat);
		
		handleResponse(response);
	}
	
	/**
	 * 
	 * @param filenameSequences
	 * @param type
	 * @param strategy
	 * @param filenameCodonUsageTable
	 * @param outputFormat
	 * @return
	 * @throws BOOSTClientException
	 * @throws IOException
	 */
	public Response invokeJuggler(
			final String filenameSequences, SequenceType type, boolean bAutoAnnotate,
			Strategy strategy, final String filenameCodonUsageTable,
			final FileFormat outputFormat)
					throws BOOSTClientException, IOException {
		
		JSONObject jsonRequestData = new JSONObject();
		
		// sequence information
		jsonRequestData.put(JSON2InputArgs.SEQUENCE_INFORMATION,  
				RequestBuilder.buildSequenceData(filenameSequences, type, bAutoAnnotate));
		
		// modification information
		jsonRequestData.put(JSON2InputArgs.MODIFICATION_INFORMATION,
				RequestBuilder.buildModificationData(strategy, filenameCodonUsageTable));
		
		// output information
		jsonRequestData.put(JSON2InputArgs.OUTPUT_INFORMATION, 
				RequestBuilder.buildOutputData(outputFormat));
		
		return this.invoke("juggler/juggle", jsonRequestData);
	}
	
	/**
	 * 
	 * @param response
	 * @throws BOOSTClientException
	 */
	public void handleResponse(Response response) 
			throws BOOSTClientException {
		
		switch(response.getStatus()) {
		case 200:	// OK
			JSONObject jsonResponseData = new JSONObject(response.readEntity(String.class));
			
			if(jsonResponseData.has(JSON2InputArgs.TEXT)) {
				System.out.println(jsonResponseData.get(JSON2InputArgs.TEXT));
			} else if(jsonResponseData.has(JSON2InputArgs.FILE)) {
				System.out.println(jsonResponseData.get(JSON2InputArgs.TEXT));
			}
			
			break;
			
		default:
			throw new BOOSTClientException(response.getStatus() + ": " + response.getStatusInfo());
		}
	}
	
	/**
	 * The verify() method verifies the sequences of a given file with the 
	 * gene synthesis constraints of a given vendor.
	 * 
	 * @param sequencesFilename ... the name of the file that contains the sequences
	 * @param type ... the type of sequences, i.e. DNA, RNA, Protein
	 * @param vendor ... the vendor
	 * 
	 * @return a map, where each key represents a file and its corresponding 
	 * value is a map, where each key represents a sequence id and its corresponding
	 * value is a list of violations represented as String objects
	 * 
	 * @throws BOOSTClientException
	 */
	public Map<String, Map<String, List<String>>> verify(
			final String sequencesFilename, SequenceType type, final Vendor vendor) 
			throws BOOSTClientException {
		
		// check if the user did a login previously
		if(null == token) {
			throw new BOOSTClientException("You must authenticate first!");
		}
		
		/*
		 * build the request
		 */
		JSONObject jsonRequestData = new JSONObject();

		// sequence information
		jsonRequestData.put(JSON2InputArgs.SEQUENCE_INFORMATION, 
				RequestBuilder.buildSequenceData(sequencesFilename, type, false));
		
		// constraints information
		jsonRequestData.put(JSON2InputArgs.CONSTRAINTS_INFORMATION, 
				RequestBuilder.buildConstraintsData(vendor));
		
		try {
			/*
			 * invoke the verify resource
			 */
			Response response = this.invoke("polisher/verify", jsonRequestData);
		
			switch(response.getStatus()) {
			case 200:	// OK
				/*
				 *  TODO: parse the response
				 */  
				return new HashMap<String, Map<String, List<String>>>();
			default:
				throw new BOOSTClientException(response.getEntity().toString());
			}
		} catch(Exception e) {
			throw new BOOSTClientException(e.getLocalizedMessage());
		}
	}
	
	
	/**
	 * The polish() method verifies the sequences in a given file against the 
	 * gene synthesis constraints of a commercial synthesis vendor. 
	 * In case of violations, the polish() method modifies the coding regions 
	 * of the sequence using the specified codon replacement strategy.
	 *  
	 * @param sequencesFilename ... the name of the file that contains the sequences
	 * @param type ... the type of the sequences, i.e. DNA, RNA, Protein
	 * @param bCodingSequences ... if the sequences are encoded in a format that does not 
	 * support sequence feature annotations and if bCoding sequences is set to true, 
	 * then are all sequences are treated as coding sequences. If the sequences are 
	 * encoded in a format that does support sequence feature annotations, then the 
	 * bCodingSequences flag is ignored. 
	 * @param vendor ... the name of commercial synthesis provider
	 * @param strategy ... the codon replacement strategy
	 * @param codonUsageTableFilename ... the name of the file that contains the codon 
	 * usage table
	 * 
	 * @throws BOOSTClientException
	 */
	public void polish(final String sequencesFilename, SequenceType type, boolean bCodingSequences,
			Vendor vendor, Strategy strategy, final String codonUsageTableFilename) 
				throws BOOSTClientException {
		
		// check if the user did a login previously
		if(null == token) {
			throw new BOOSTClientException("You must authenticate first!");
		}
		
		/*
		 * build the request
		 */
		JSONObject jsonRequestData = new JSONObject();

		// sequence information
		jsonRequestData.put(JSON2InputArgs.SEQUENCE_INFORMATION, 
				RequestBuilder.buildSequenceData(sequencesFilename, type, bCodingSequences));
		
		// constraints information
		jsonRequestData.put(JSON2InputArgs.CONSTRAINTS_INFORMATION, 
				RequestBuilder.buildConstraintsData(vendor));

		/*
		 * TODO: -- modification information
		 */ 
//		jsonRequest.put(JSON2InputArgs.MODIFICATION_INFORMATION, 
//				RequestBuilder.buildModificationData(strategy, codonUsageTableFilename);
		
		/*
		 * invoke the resource
		 */
		try {
			/*
			 * invoke the verify resource
			 */
			Response response = this.invoke("polisher/verify", jsonRequestData);
		
			switch(response.getStatus()) {
			case 200:	// OK
				/*
				 *  TODO: parse the response
				 */  
				return;
			default:
				throw new BOOSTClientException(response.getEntity().toString());
			}
		} catch(Exception e) {
			throw new BOOSTClientException(e.getLocalizedMessage());
		}
	}
	
	/**
	 * 
	 * @param resource
	 * @param jsonRequestData
	 * @return
	 * @throws BOOSTClientException
	 */
	public Response invoke(final String resource, final JSONObject jsonRequestData) 
			throws BOOSTClientException {
		
		WebTarget webTarget = client.target(SPL_REST_URL).path(resource);
		
		Invocation.Builder invocationBuilder =  webTarget.request(MediaType.APPLICATION_JSON);
		invocationBuilder.header("authorization", this.token);
		
		try {
			Response response = invocationBuilder.post(
					Entity.entity(jsonRequestData.toString(), MediaType.APPLICATION_JSON));
			
			switch(response.getStatus()) {
			case 200:	// OK
				return response;
			default:
				throw new BOOSTClientException(response.getEntity().toString());
			}
		} catch(Exception e) {
			throw new BOOSTClientException(e.getLocalizedMessage());
		}
	}
}