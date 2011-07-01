/**
 *
 * History:
 *   11-5-15 8:39 Pm Created by ZGong
 */
package rb;

import java.io.*;
import java.net.*;
import java.util.*;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.google.gson.Gson;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.ui.Messages;

/**
 * Created by ZGong.
 *
 * @version 1.0 11-5-15 8:39 Pm
 */
public class ReviewBoardClient {

  public static boolean postReview(ReviewSettings settings, final ProgressIndicator progressIndicator) {
    ReviewBoardClient reviewBoardClient = new ReviewBoardClient(settings.getServer(), settings.getUsername(), settings.getPassword());
    try {
      String reviewId = settings.getReviewId();
      if (reviewId == null || "".equals(reviewId)) {
        progressIndicator.setText("Creating review draft...");
        NewReviewResponse newRequest = reviewBoardClient.createNewRequest(settings.getSvnRoot());
        progressIndicator.setText("Create new request:" + newRequest.review_request);
        System.out.println(newRequest.review_request.id);
        reviewId = newRequest.review_request.id;
        settings.setReviewId(reviewId);
      }
      progressIndicator.setText("Updating draft...");
      DraftResponse response = reviewBoardClient.updateDraft(reviewId, settings);
      if (!response.isOk()) {
        final Response finalResponse = response;
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            JOptionPane.showMessageDialog(null, finalResponse.err, "Warning", JOptionPane.WARNING_MESSAGE);
          }
        }
        );
        return false;
      }
      response = reviewBoardClient.updateDraftWithLimitField(reviewId, settings);
      if (!response.isOk()) {
        progressIndicator.setText(response.err);
      }
      progressIndicator.setText("Draft is updated");
      progressIndicator.setText("Uploading diff...");
      final Response diff = reviewBoardClient.diff(reviewId, settings.getSvnBasePath(), settings.getDiff());
      if (!diff.isOk()) {
        SwingUtilities.invokeLater(new Runnable() {
          @Override
          public void run() {
            JOptionPane.showMessageDialog(null, diff.err, "Warning", JOptionPane.WARNING_MESSAGE);
          }
        }
        );
        return false;
      }
      progressIndicator.setText("Diff is updated");

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return true;
  }

  String apiUrl;

  ReviewBoardClient(String server, final String username, final String password) {
    this.apiUrl = server + "/api/";
    class MyAuthenticator extends Authenticator {
      public PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(username, password.toCharArray());
      }
    }
    Authenticator.setDefault(new MyAuthenticator());
  }

  public static void loadReview(ReviewSettings settings, String reviewId) throws Exception {
    ReviewBoardClient rb = new ReviewBoardClient(settings.getServer(), settings.getUsername(), settings.getPassword());
    ReviewRequest reviewRequest;
    try {
      DraftResponse reviewInfo = rb.getReviewInfoAsDraft(reviewId);
      reviewRequest = reviewInfo.draft;
    } catch (Exception e) {
      NewReviewResponse reviewInfoAsPublished = rb.getReviewInfoAsPublished(reviewId);
      reviewRequest = reviewInfoAsPublished.review_request;
    }

    if (reviewRequest != null) {
      settings.setBranch(reviewRequest.branch);
      settings.setDescription(reviewRequest.description);
      settings.setBugsClosed(reviewRequest.getBugs_closed());
      settings.setPeople(reviewRequest.getTarget_people());
      settings.setGroup(reviewRequest.getTarget_group());
    } else {
      Messages.showErrorDialog("No such review id:" + reviewId, "Error");
    }
  }

  class HttpClient {
    public String httpGet(String path) throws Exception {
      URL url = new URL(apiUrl + path);
      System.out.println("Http get:" + url);
      URLConnection urlConnection = url.openConnection();
      InputStream inputStream = urlConnection.getInputStream();
      StringBuilder sb = new StringBuilder();
      BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    }

    public String httpRequest(String path, String method, Map<String, String> params) throws Exception {
      URL url = new URL(apiUrl + path);
      System.out.println("Http " + method + ":" + url);
      URLConnection urlConnection = url.openConnection();
      urlConnection.setDoInput(true);
      urlConnection.setDoOutput(true);
      HttpURLConnection http = (HttpURLConnection) urlConnection;
      http.setRequestMethod(method);
      OutputStream outputStream = urlConnection.getOutputStream();
      PrintWriter pw = new PrintWriter(outputStream, true);
      for (String key : params.keySet()) {
        pw.println(key + "=" + params.get(key));
      }
      pw.flush();
      InputStream inputStream = urlConnection.getInputStream();
      StringBuilder sb = new StringBuilder();
      BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    }

    public String httpRequestWithMultiplePart(String path, String method, Map<String, Object> params) throws Exception {
      URL url = new URL(apiUrl + path);
      System.out.println("Http " + method + ":" + url);
      URLConnection urlConnection = url.openConnection();
      urlConnection.setDoInput(true);
      urlConnection.setDoOutput(true);
      HttpURLConnection http = (HttpURLConnection) urlConnection;
      http.setRequestMethod(method);
      ClientHttpRequest chr = new ClientHttpRequest(http);
      InputStream inputStream = chr.post(params);
      StringBuilder sb = new StringBuilder();
      BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
      String line;
      while ((line = br.readLine()) != null) {
        sb.append(line);
      }
      return sb.toString();
    }

    public String httpPost(String path, Map<String, Object> params) throws Exception {
      return httpRequestWithMultiplePart(path, "POST", params);
    }

    private String httpPut(String path, Map<String, Object> params) throws Exception {
      return httpRequestWithMultiplePart(path, "PUT", params);
    }
  }


  public DraftResponse updateDraft(String reviewId, ReviewSettings settings) throws Exception {
    String path = String.format("review-requests/%s/draft/", reviewId);
    Map<String, Object> params = new HashMap<String, Object>();
    addParams(params, "summary", settings.getSummary());
    addParams(params, "branch", settings.getBranch());
    addParams(params, "bugs_closed", settings.getBugsClosed());
    addParams(params, "description", settings.getDescription());
    String json = new HttpClient().httpPut(path, params);
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, DraftResponse.class);
  }

  public DraftResponse updateDraftWithLimitField(String reviewId, ReviewSettings settings) throws Exception {
    String path = String.format("review-requests/%s/draft/", reviewId);
    Map<String, Object> params = new HashMap<String, Object>();
    addParams(params, "target_groups", settings.getGroup());
    addParams(params, "target_people", settings.getPeople());
    String json;
    try {
      json = new HttpClient().httpPut(path, params);
    } catch (Exception e) {
      return new DraftResponse("error", "Invalid group or people");
    }
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, DraftResponse.class);
  }


  private void addParams(Map<String, Object> params, String key, String value) {
    if (value != null && !"".equals(value)) {
      params.put(key, value);
    }
  }

  public NewReviewResponse createNewRequest(String repository) throws Exception {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("repository", repository);
    String json = new HttpClient().httpPost("review-requests/", params);
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, NewReviewResponse.class);
  }

  public Response diff(String reviewId, String basedir, String diff) throws Exception {
    String path = String.format("review-requests/%s/diffs/", reviewId);
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("basedir", basedir);
    params.put("path", new MemoryFile("review.diff", diff));

    String json = new HttpClient().httpPost(path, params);
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, Response.class);
  }

  public DraftResponse getReviewInfoAsDraft(String reviewId) throws Exception {
    String path = String.format("review-requests/%s/draft/", reviewId);
    String json = new HttpClient().httpGet(path);
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, DraftResponse.class);
  }

  public NewReviewResponse getReviewInfoAsPublished(String reviewId) throws Exception {
    String path = String.format("review-requests/%s/", reviewId);
    String json = new HttpClient().httpGet(path);
    System.out.println(json);
    Gson gson = new Gson();
    return gson.fromJson(json, NewReviewResponse.class);
  }
}

class Href {
  String href;
  String method;

  @Override
  public String toString() {
    return "Href{" +
        "href='" + href + '\'' +
        ", method='" + method + '\'' +
        '}';
  }
}

class Response {
  public static final Response OK = new Response("ok");
  public static final Response ERROR = new Response("error");

  Response() {
  }

  Response(String stat) {
    this.stat = stat;
  }

  String stat;
  String err;

  public Response(String stat, String err) {
    this.stat = stat;
    this.err = err;
  }

  public boolean isOk() {
    return "ok".equals(stat);
  }
}

class People extends Href {
  String title;
}

class Group extends Href {
  String title;
}

class ReviewRequest {
  String status;
  String id;
  String last_updated;
  String description;
  Map<String, Href> links;
  String[] bugs_closed;
  String summary;
  String branch;
  People[] target_people;
  Group[] target_groups;

  public String getBugs_closed() {
    StringBuilder sb = new StringBuilder();
    for (String s : bugs_closed) {
      sb.append(s).append(",");
    }
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
      sb.deleteCharAt(sb.length() - 1);
    }
    return sb.toString();
  }

  interface Transform<T> {
    public String transform(T t);
  }

  private <T> String join(T[] t, char c, Transform<T> cal) {
    StringBuilder sb = new StringBuilder();
    for (T o : t) {
      sb.append(cal.transform(o)).append(c);
    }
    if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
      sb.deleteCharAt(sb.length() - 1);
    }
    return sb.toString();
  }

  public String getTarget_people() {
    return join(target_people, ',', new Transform<People>() {
      @Override
      public String transform(People people) {
        return people.title;
      }
    });
  }

  public String getTarget_group() {
    return join(target_groups, ',', new Transform<Group>() {
      @Override
      public String transform(Group group) {
        return group.title;
      }
    });
  }

  @Override
  public String toString() {
    return "ReviewRequest{" +
        "status='" + status + '\'' +
        ", id='" + id + '\'' +
        ", last_updated='" + last_updated + '\'' +
        ", description='" + description + '\'' +
        ", links=" + links +
        ", bugs_closed='" + bugs_closed + '\'' +
        ", summary='" + summary + '\'' +
        ", branch='" + branch + '\'' +
        ", target_people=" + (target_people == null ? null : Arrays.asList(target_people)) +
        ", target_groups=" + (target_groups == null ? null : Arrays.asList(target_groups)) +
        '}';
  }
}

class NewReviewResponse extends Response {
  ReviewRequest review_request;


  @Override
  public String toString() {
    return "NewReviewResponse{" +
        "stat='" + stat + '\'' +
        ", review_request=" + review_request +
        '}';
  }
}

class DraftResponse extends Response {
  DraftResponse(String stat, String err) {
    super(stat, err);
  }

  ReviewRequest draft;

  @Override
  public String toString() {
    return "DraftResponse{" +
        "draft=" + draft +
        '}';
  }
}
