package com.mozilla.telemetry.contextualservices;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.mozilla.telemetry.ingestion.core.Constant.Attribute;
import com.mozilla.telemetry.metrics.PerDocTypeCounter;
import com.mozilla.telemetry.transforms.FailureMessage;
import com.mozilla.telemetry.transforms.PubsubConstraints;
import com.mozilla.telemetry.util.BeamFileInputStream;
import com.mozilla.telemetry.util.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.beam.sdk.io.gcp.pubsub.PubsubMessage;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.WithFailures.ExceptionElement;
import org.apache.beam.sdk.transforms.WithFailures.Result;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;

/**
 * Extract reporting URL from document and filter out unknown URLs.
 */
public class ParseReportingUrl extends
    PTransform<PCollection<PubsubMessage>, Result<PCollection<SponsoredInteraction>, PubsubMessage>> {

  private final String urlAllowList;

  private static transient Set<String> singletonAllowedImpressionUrls;
  private static transient Set<String> singletonAllowedClickUrls;

  private static final String NS_DESKTOP = "contextual-services";
  private static final String DT_TOPSITES_IMPRESSION = "topsites-impression";
  private static final String DT_TOPSITES_CLICK = "topsites-click";
  private static final String DT_QUICKSUGGEST_IMPRESSION = "quicksuggest-impression";
  private static final String DT_QUICKSUGGEST_CLICK = "quicksuggest-click";

  // Values from the user_agent_os attribute
  private static final String OS_WINDOWS = "Windows";
  private static final String OS_MAC = "Macintosh";
  private static final String OS_LINUX = "Linux";
  private static final String OS_ANDROID = "Android";

  // Values used for the os-family API parameter
  private static final String PARAM_WINDOWS = "Windows";
  private static final String PARAM_MAC = "macOS";
  private static final String PARAM_LINUX = "Linux";
  private static final String PARAM_ANDROID = "Android";

  // Values for the click-status API parameter
  public static final String CLICK_STATUS_ABUSE = "64";
  public static final String CLICK_STATUS_GHOST = "65";

  // Threshold for IP reputation considered likely abuse.
  private static final int IP_REPUTATION_THRESHOLD = 70;

  public static ParseReportingUrl of(String urlAllowList) {
    return new ParseReportingUrl(urlAllowList);
  }

  private ParseReportingUrl(String urlAllowList) {
    this.urlAllowList = urlAllowList;
  }

  @Override
  public Result<PCollection<SponsoredInteraction>, PubsubMessage> expand(
      PCollection<PubsubMessage> messages) {
    return messages.apply(MapElements.into(TypeDescriptor.of(SponsoredInteraction.class))
        .via((PubsubMessage message) -> {
          message = PubsubConstraints.ensureNonNull(message);

          ObjectNode payload;
          try {
            payload = Json.readObjectNode(message.getPayload());
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }

          try {
            loadAllowedUrls();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }

          Map<String, String> attributes = new HashMap<>(message.getAttributeMap());
          String namespace = Objects
              .requireNonNull(message.getAttribute(Attribute.DOCUMENT_NAMESPACE));
          String docType = Objects.requireNonNull(message.getAttribute(Attribute.DOCUMENT_TYPE));

          SponsoredInteraction.Builder interactionBuilder = SponsoredInteraction.builder();

          interactionBuilder.setOriginalNamespace(namespace);
          interactionBuilder.setOriginalDocType(docType);

          // set fields based on namespace/doctype combos
          if (NS_DESKTOP.equals(namespace)) {
            interactionBuilder.setFormFactor(SponsoredInteraction.FORM_DESKTOP);
            // potential docTypes here are
            // `topsites-impression`, `topsites-click`
            // `quicksuggest-impression`, `quicksuggest-click`
            if (DT_TOPSITES_IMPRESSION.equals(docType) || DT_QUICKSUGGEST_IMPRESSION.equals(docType)) {
              interactionBuilder.setInteractionType(SponsoredInteraction.INTERACTION_IMPRESSION);
            } else if (DT_TOPSITES_CLICK.equals(docType) || DT_QUICKSUGGEST_CLICK.equals(docType)) {
              interactionBuilder.setInteractionType(SponsoredInteraction.INTERACTION_CLICK);
            } else {
              throw new InvalidAttributeException("Received unexpected docType: " + docType, docType);
            }
          } else {
            interactionBuilder.setFormFactor(SponsoredInteraction.FORM_PHONE);
            // enforce that the only mobile docType is `topsites-impression`
            if (!DT_TOPSITES_IMPRESSION.equals(docType)) {
              throw new InvalidAttributeException("Unexpected docType for mobile ping: " + docType, docType);
            }
            ArrayNode events = payload.withArray("events");
            if (events.size() != 1) {
              throw new RuntimeException("expect exactly 1 event in ping.");
            }
            JsonNode event = events.get(0);
            // potential event names are `contile_impression` and `contile_click`
            String eventName = event.path("name").asText();
            if ("contile_impression".equals(eventName)) {
              interactionBuilder.setInteractionType(SponsoredInteraction.INTERACTION_IMPRESSION);
            } else if ("contile_click".equals(eventName)) {
              interactionBuilder.setInteractionType(SponsoredInteraction.INTERACTION_CLICK);
            } else {
              throw new InvalidAttributeException("Received unexpected event name: " + eventName, eventName);
            }
          }

          // Set the source based on the value of the docType
          if (DT_TOPSITES_CLICK.equals(docType) || DT_TOPSITES_IMPRESSION.equals(docType)) {
            interactionBuilder.setSource(SponsoredInteraction.SOURCE_TOPSITES);
          } else if (DT_QUICKSUGGEST_IMPRESSION.equals(docType) || DT_QUICKSUGGEST_CLICK.equals(docType)) {
            interactionBuilder.setSource(SponsoredInteraction.SOURCE_SUGGEST);
          } else {
            throw new InvalidAttributeException("Unexpected docType: " + docType, docType);
          }

          // Store context_id for click counting in subsequent transforms.
          Optional.ofNullable(payload.path(Attribute.CONTEXT_ID).textValue()) //
              .ifPresent(interactionBuilder::setContextId);

          // Store request_id for counting in subsequent transforms.
          Optional.ofNullable(payload.path(Attribute.REQUEST_ID).textValue()) //
              .ifPresent(interactionBuilder::setRequestId);

          SponsoredInteraction interaction = interactionBuilder.build();
          String reportingUrl = extractReportingUrl(payload);
          ParsedReportingUrl urlParser = new ParsedReportingUrl(reportingUrl);

          if (!isUrlValid(urlParser.getReportingUrl(),
              Objects.requireNonNull(interaction.getInteractionType()))) {
            PerDocTypeCounter.inc(attributes, "rejected_nonnull_url");
            throw new ParsedReportingUrl.InvalidUrlException(
                "Reporting URL host not found in allow list: " + reportingUrl);
          }

          // ensure parameters based on source and interaction type
          if (SponsoredInteraction.INTERACTION_CLICK.equals(interaction.getInteractionType())
              && SponsoredInteraction.SOURCE_TOPSITES.equals(interaction.getSource())) {
            requireParamPresent(urlParser, "ctag");
            requireParamPresent(urlParser, "version");
            requireParamPresent(urlParser, "key");
            requireParamPresent(urlParser, "ci");
          } else if (SponsoredInteraction.INTERACTION_CLICK.equals(interaction.getInteractionType())
              && SponsoredInteraction.SOURCE_SUGGEST.equals(interaction.getSource())) {
            // Per https://bugzilla.mozilla.org/show_bug.cgi?id=1738974
            requireParamPresent(urlParser, "ctag");
            requireParamPresent(urlParser, "custom-data");
            requireParamPresent(urlParser, "sub1");
            requireParamPresent(urlParser, "sub2");
          } else if (SponsoredInteraction.INTERACTION_IMPRESSION
              .equals(interaction.getInteractionType())
              && SponsoredInteraction.SOURCE_TOPSITES.equals(interaction.getSource())) {
            requireParamPresent(urlParser, "id");
          } else if (SponsoredInteraction.INTERACTION_IMPRESSION
              .equals(interaction.getInteractionType())
              && SponsoredInteraction.SOURCE_SUGGEST.equals(interaction.getSource())) {
            // Per https://bugzilla.mozilla.org/show_bug.cgi?id=1738974
            requireParamPresent(urlParser, "custom-data");
            requireParamPresent(urlParser, "sub1");
            requireParamPresent(urlParser, "sub2");
            requireParamPresent(urlParser, "partner");
            requireParamPresent(urlParser, "adv-id");
            requireParamPresent(urlParser, "v");
          }

          // We only add these dimensions for topsites, not quicksuggest per
          // https://bugzilla.mozilla.org/show_bug.cgi?id=1738974
          if (SponsoredInteraction.SOURCE_TOPSITES.equals(interaction.getSource())) {

            if (!payload.hasNonNull(Attribute.NORMALIZED_COUNTRY_CODE)) {
              throw new RejectedMessageException(
                  "Missing required payload value " + Attribute.NORMALIZED_COUNTRY_CODE, "country");
            }
            urlParser.addQueryParam(ParsedReportingUrl.PARAM_COUNTRY_CODE,
                payload.get(Attribute.NORMALIZED_COUNTRY_CODE).asText());

            urlParser.addQueryParam(ParsedReportingUrl.PARAM_REGION_CODE,
                attributes.get(Attribute.GEO_SUBDIVISION1));
            urlParser.addQueryParam(ParsedReportingUrl.PARAM_OS_FAMILY,
                getOsParam(attributes.get(Attribute.USER_AGENT_OS)));
            urlParser.addQueryParam(ParsedReportingUrl.PARAM_FORM_FACTOR,
                interaction.getFormFactor());

            urlParser.addQueryParam(ParsedReportingUrl.PARAM_DMA_CODE,
                message.getAttribute(Attribute.GEO_DMA_CODE));
          }

          // We only add these dimensions for topsites clicks, not quicksuggest per
          // https://bugzilla.mozilla.org/show_bug.cgi?id=1738974
          // We are also limiting this to desktop.
          if (SponsoredInteraction.FORM_DESKTOP.equals(interaction.getFormFactor())
              && SponsoredInteraction.SOURCE_TOPSITES.equals(interaction.getSource())
              && SponsoredInteraction.INTERACTION_CLICK.equals(interaction.getInteractionType())) {
            String userAgentVersion = attributes.get(Attribute.USER_AGENT_VERSION);
            if (userAgentVersion == null) {
              throw new RejectedMessageException(
                  "Missing required attribute " + Attribute.USER_AGENT_VERSION,
                  "user_agent_version");
            }
            urlParser.addQueryParam(ParsedReportingUrl.PARAM_PRODUCT_VERSION,
                "firefox_" + userAgentVersion);
            String ipReputationString = attributes.get(Attribute.X_FOXSEC_IP_REPUTATION);
            Integer ipReputation = null;
            try {
              ipReputation = Integer.parseInt(ipReputationString);
            } catch (NumberFormatException ignore) {
              // pass
            }
            if (ipReputation != null && ipReputation < IP_REPUTATION_THRESHOLD) {
              urlParser.addQueryParam(ParsedReportingUrl.PARAM_CLICK_STATUS, CLICK_STATUS_ABUSE);
            }
          }

          reportingUrl = urlParser.toString();
          PerDocTypeCounter.inc(attributes, "valid_url");
          return interaction.toBuilder().setReportingUrl(reportingUrl).build();
        }).exceptionsInto(TypeDescriptor.of(PubsubMessage.class))
        .exceptionsVia((ExceptionElement<PubsubMessage> ee) -> {
          try {
            throw ee.exception();
          } catch (UncheckedIOException | IllegalArgumentException
              | ParsedReportingUrl.InvalidUrlException | RejectedMessageException e) {
            return FailureMessage.of(ParseReportingUrl.class.getSimpleName(), ee.element(),
                ee.exception());
          }
        }));
  }

  @VisibleForTesting
  boolean isUrlValid(URL url, String interactionType) {
    Set<String> allowedUrls;

    if (interactionType.equals(SponsoredInteraction.INTERACTION_CLICK)) {
      allowedUrls = singletonAllowedClickUrls;
    } else if (interactionType.equals(SponsoredInteraction.INTERACTION_IMPRESSION)) {
      allowedUrls = singletonAllowedImpressionUrls;
    } else {
      throw new IllegalArgumentException("Invalid interaction type: " + interactionType);
    }

    if (allowedUrls.contains(url.getHost())) {
      return true;
    }

    // check for subdomains (e.g. allow mozilla.test.com but not mozillatest.com)
    return allowedUrls.stream().map(allowedUrl -> "." + allowedUrl)
        .anyMatch(url.getHost()::endsWith);
  }

  /**
   * Return the value used for the os-family parameter of the API
   * associated with the user_agent_os attribute.
   *
   * @throws RejectedMessageException if the given OS value is not recognized
   */
  private String getOsParam(String userAgentOs) {
    if (userAgentOs == null) {
      throw new RejectedMessageException("Missing required OS attribute", "os");
    }
    if (userAgentOs.startsWith(OS_WINDOWS)) {
      return PARAM_WINDOWS;
    } else if (userAgentOs.startsWith(OS_MAC)) {
      return PARAM_MAC;
    } else if (userAgentOs.startsWith(OS_LINUX)) {
      return PARAM_LINUX;
    } else if (userAgentOs.startsWith(OS_ANDROID)) {
      return PARAM_ANDROID;
    } else {
      throw new RejectedMessageException("Unrecognized OS attribute: " + userAgentOs, "os");
    }
  }

  /**
   * Returns a mapping from a two-column CSV file where column one
   * is the key and column two is the value.
   *
   * @throws IOException if the given file path cannot be read
   * @throws IllegalArgumentException if the given file location cannot be retrieved
   *     or the CSV format is incorrect
   */
  private List<String[]> readPairsFromFile(String fileLocation, String paramName)
      throws IOException {
    if (fileLocation == null) {
      throw new IllegalArgumentException("--" + paramName + " must be defined");
    }

    try (InputStream inputStream = BeamFileInputStream.open(fileLocation);
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader reader = new BufferedReader(inputStreamReader)) {
      List<String[]> pairs = new ArrayList<>();

      while (reader.ready()) {
        String line = reader.readLine();

        if (line != null && !line.isEmpty()) {
          String[] separated = line.split(",");

          if (separated.length != 2) {
            throw new IllegalArgumentException(
                "Invalid mapping: " + line + "; two-column csv expected");
          }

          pairs.add(separated);
        }
      }

      return pairs;
    } catch (IOException e) {
      throw new IOException("Exception thrown while fetching " + paramName, e);
    }
  }

  private String extractReportingUrl(ObjectNode payload) {
    if (!payload.path(Attribute.REPORTING_URL).isMissingNode()) {
      return payload.path(Attribute.REPORTING_URL).asText();
    }
    return payload.path("metrics").path("url2").path("top_sites_contile_reporting_url").asText();
  }

  @VisibleForTesting
  List<Set<String>> loadAllowedUrls() throws IOException {
    if (singletonAllowedImpressionUrls == null || singletonAllowedClickUrls == null) {
      Set<String> allowedImpressionUrls = new HashSet<>();
      Set<String> allowedClickUrls = new HashSet<>();

      List<String[]> urlToActionTypePairs = readPairsFromFile(urlAllowList, "urlAllowList");

      // 0th element is site, 1st element is action type (click/impression)
      for (String[] urlToActionType : urlToActionTypePairs) {
        if (urlToActionType[1].equals("click")) {
          allowedClickUrls.add(urlToActionType[0]);
        } else if (urlToActionType[1].equals("impression")) {
          allowedImpressionUrls.add(urlToActionType[0]);
        } else {
          throw new IllegalArgumentException(
              "Invalid action type in url allow list: " + urlToActionType[1]);
        }
      }

      singletonAllowedImpressionUrls = allowedImpressionUrls;
      singletonAllowedClickUrls = allowedClickUrls;
    }
    return Arrays.asList(singletonAllowedClickUrls, singletonAllowedImpressionUrls);
  }

  private static void requireParamPresent(ParsedReportingUrl reportingUrl, String paramName) {
    if (reportingUrl.getQueryParam(paramName) == null) {
      throw new RejectedMessageException("Missing required url query parameter: " + paramName,
          paramName);
    }
  }
}
