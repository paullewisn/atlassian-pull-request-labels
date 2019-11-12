package io.reconquest.bitbucket.labels;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.atlassian.activeobjects.external.ActiveObjects;

import io.reconquest.bitbucket.labels.ao.AOLabel;
import io.reconquest.bitbucket.labels.ao.AOLabelItem;
import net.java.ao.DBParam;
import net.java.ao.Query;

public class Store {
  private final ActiveObjects ao;
  private static Logger log = Logger.getLogger(Store.class.getSimpleName());

  public Store(ActiveObjects ao) {
    this.ao = ao;
  }

  private Label[] find(String clause, Object... params) {
    AOLabelItem[] aoItems = this.ao.find(AOLabelItem.class, getJoinQuery(clause, params));

    Set<Integer> setLabelIds = new HashSet<Integer>();
    for (AOLabelItem item : aoItems) {
      setLabelIds.add(item.getLabelId());
    }

    Integer[] labelIds = setLabelIds.toArray(new Integer[0]);
    if (labelIds.length == 0) {
      return Label.Factory.getLabels(aoItems, null);
    }

    String condition = conditionIn(labelIds);

    AOLabel[] aoLabels = this.ao.find(
        AOLabel.class, Query.select().from(AOLabel.class).where("ID in (" + condition + ")"));

    return Label.Factory.getLabels(aoItems, aoLabels);
  }

  public Label[] find(int projectId, int repositoryId, long pullRequestId) {
    return this.find(
        "item.PROJECT_ID = ? AND item.REPOSITORY_ID = ? AND item.PULL_REQUEST_ID = ?",
        projectId,
        repositoryId,
        pullRequestId);
  }

  public Label[] find(int projectId, int repositoryId) {
    return this.find("item.PROJECT_ID = ? AND item.REPOSITORY_ID = ?", projectId, repositoryId);
  }

  public Label[] find(int projectId, int repositoryId, String name) {
    return this.find(
        "item.PROJECT_ID = ? AND item.REPOSITORY_ID = ? AND label.NAME LIKE ?",
        projectId,
        repositoryId,
        name);
  }

  public Label[] find(int projectId, int repositoryId, long pullRequestId, String name) {
    return this.find(
        "item.PROJECT_ID = ? AND item.REPOSITORY_ID = ? AND item.PULL_REQUEST_ID = ?"
            + " AND label.NAME LIKE ?",
        projectId,
        repositoryId,
        pullRequestId,
        name);
  }

  public Label[] find(Integer[] repositories) {
    String[] ids = new String[repositories.length];
    for (int i = 0; i < repositories.length; i++) {
      ids[i] = String.valueOf(repositories[i]);
    }

    String query = String.join(",", ids);
    return this.find("item.REPOSITORY_ID IN (" + query + ")");
  }

  public AOLabel createAOLabel(int projectId, int repositoryId, String name, String color) {
    try {
      AOLabel label = this.ao.create(
          AOLabel.class,
          new DBParam("PROJECT_ID", projectId),
          new DBParam("REPOSITORY_ID", repositoryId),
          new DBParam("NAME", name),
          new DBParam("COLOR", color),
          new DBParam("HASH", hash(projectId, repositoryId, name)));
      return label;
    } catch (Exception e) { // No way to handle duplicate hash
      AOLabel[] labels = this.ao.find(AOLabel.class, Query.select()
          .from(AOLabel.class)
          .where(
              "PROJECT_ID = ? AND REPOSITORY_ID = ? AND NAME = ?", projectId, repositoryId, name));
      if (labels.length == 0) {
        // throw what we have if we can't find the same label
        throw e;
      }

      return labels[0];
    }
  }

  public AOLabelItem createAOLabelItem(
      int projectId, int repositoryId, long pullRequestId, AOLabel label) {
    return this.ao.create(
        AOLabelItem.class,
        new DBParam("LABEL_ID", label.getID()),
        new DBParam("PROJECT_ID", projectId),
        new DBParam("REPOSITORY_ID", repositoryId),
        new DBParam("PULL_REQUEST_ID", pullRequestId));
  }

  public int create(
      int projectId, int repositoryId, long pullRequestId, String name, String color) {
    AOLabel label = createAOLabel(projectId, repositoryId, name, color);
    AOLabelItem item = createAOLabelItem(projectId, repositoryId, pullRequestId, label);
    return item.getID();
  }

  public void update(int projectId, int repositoryId, int labelId, String name, String color)
      throws Exception {
    AOLabel[] labels = this.ao.find(AOLabel.class, Query.select()
        .from(AOLabel.class)
        .where(
            "PROJECT_ID = ? AND REPOSITORY_ID = ? AND ID = ?", projectId, repositoryId, labelId));
    if (labels.length == 0) {
      log.warning("no labels found with such conditions");
      return;
    }

    AOLabel label = labels[0];

    label.setName(name);
    label.setColor(color);
    label.setHash(hash(projectId, repositoryId, name));

    label.save();
  }

  private Query getJoinQuery(String clause, Object... params) {
    return Query.select()
        .from(AOLabelItem.class)
        .alias(AOLabelItem.class, "item")
        .join(AOLabel.class, "label.ID = item.LABEL_ID")
        .alias(AOLabel.class, "label")
        .where(clause, params);
  }

  public void flush() {
    ao.flush();
  }

  public void deleteItems(Label[] labels) {
    ao.deleteWithSQL(AOLabelItem.class, "ID IN (" + conditionIn(getItemIds(labels)) + ")");
  }

  public String hash(AOLabel label) {
    return hash(label.getProjectId(), label.getRepositoryId(), label.getName());
  }

  private String hash(int projectId, int repositoryId, String name) {
    String token = String.valueOf(projectId) + "@" + String.valueOf(repositoryId) + "@" + name;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] encoded = digest.digest(token.getBytes(StandardCharsets.UTF_8));
      return bytesToHex(encoded);
    } catch (NoSuchAlgorithmException e) {
      log.log(Level.SEVERE, "unable to encode label hash", e);
      return "";
    }
  }

  private String bytesToHex(byte[] hash) {
    StringBuffer hexString = new StringBuffer();
    for (int i = 0; i < hash.length; i++) {
      String hex = Integer.toHexString(0xff & hash[i]);
      if (hex.length() == 1) hexString.append('0');
      hexString.append(hex);
    }
    return hexString.toString();
  }

  private Integer[] getItemIds(Label[] labels) {
    Integer[] ids = new Integer[labels.length];
    for (int i = 0; i < labels.length; i++) {
      ids[i] = Integer.valueOf(labels[i].getItemId());
    }
    return ids;
  }

  private <T> String conditionIn(T[] items) {
    String[] strings = new String[items.length];
    for (int i = 0; i < items.length; i++) {
      strings[i] = String.valueOf(items[i]);
    }

    return String.join(",", strings);
  }
}
