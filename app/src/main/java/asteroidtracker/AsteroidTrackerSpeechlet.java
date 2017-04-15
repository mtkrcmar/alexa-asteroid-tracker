/**
    Copyright 2014-2015 Amazon.com, Inc. or its affiliates. All Rights Reserved.

    Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance with the License. A copy of the License is located at

        http://aws.amazon.com/apache2.0/

    or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package asteroidtracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazon.speech.slu.Intent;
import com.amazon.speech.slu.Slot;
import com.amazon.speech.speechlet.IntentRequest;
import com.amazon.speech.speechlet.LaunchRequest;
import com.amazon.speech.speechlet.Session;
import com.amazon.speech.speechlet.SessionEndedRequest;
import com.amazon.speech.speechlet.SessionStartedRequest;
import com.amazon.speech.speechlet.Speechlet;
import com.amazon.speech.speechlet.SpeechletException;
import com.amazon.speech.speechlet.SpeechletResponse;
import com.amazon.speech.ui.OutputSpeech;
import com.amazon.speech.ui.PlainTextOutputSpeech;
import com.amazon.speech.ui.SsmlOutputSpeech;
import com.amazon.speech.ui.Reprompt;
import com.amazon.speech.ui.SimpleCard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class AsteroidTrackerSpeechlet implements Speechlet {
    private static final Logger log = LoggerFactory.getLogger(AsteroidTrackerSpeechlet.class);

    /**
     * URL prefix to download history content from Wikipedia.
     */
    private static final String URL_PREFIX = "https://api.nasa.gov/neo/rest/v1/feed?";
    private static final String URL_START_DATE = "start_date=";
    private static final String URL_END_DATE = "&end_date=";
    private static final String URL_POSTFIX = "&detailed=false&api_key=DEMO_KEY";

    /**
     * Constant defining number of events to be read at one time.
     */
    private static final int PAGINATION_SIZE = 1;

    /**
     * Length of the delimiter between individual events.
     */
    private static final int DELIMITER_SIZE = 2;

    /**
     * Constant defining session attribute key for the event index.
     */
    private static final String SESSION_INDEX = "index";

    /**
     * Constant defining session attribute key for the event text key for date of events.
     */
    private static final String SESSION_TEXT = "text";

    /**
     * Constant defining session attribute key for the intent slot key for the date of events.
     */
    private static final String SLOT_DAY = "day";

    /**
     * Size of events from Wikipedia response.
     */
    private static final int SIZE_OF_EVENTS = 10;

    /**
     * Array of month names.
     */
    private static final String[] MONTH_NAMES = {
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December"
    };

    private static final String INFORMATION_TEXT = "With Asteroid Tracker, you can get near earth object events for any day of the year."
            + " For example, you could say today, or July fourth."
            + " So, which day do you want?";
    @Override
    public void onSessionStarted(final SessionStartedRequest request, final Session session)
            throws SpeechletException {
        log.info("onSessionStarted requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        // any initialization logic goes here
    }

    @Override
    public SpeechletResponse onLaunch(final LaunchRequest request, final Session session)
            throws SpeechletException {
        log.info("onLaunch requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        return getWelcomeResponse();
    }

    @Override
    public SpeechletResponse onIntent(final IntentRequest request, final Session session)
            throws SpeechletException {
        log.info("onIntent requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        Intent intent = request.getIntent();
        String intentName = intent.getName();

        if ("GetFirstEventIntent".equals(intentName)) {
            return handleFirstEventRequest(intent, session);
        } 
        
        else if ("GetNextEventIntent".equals(intentName)) {
            return handleNextEventRequest(session);
        }
        
         else if ("AMAZON.HelpIntent".equals(intentName)) {
            return newAskResponse(INFORMATION_TEXT, false, INFORMATION_TEXT, false);
        }
        
         else if ("AMAZON.StopIntent".equals(intentName)) {
            PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
            outputSpeech.setText("Goodbye");

            return SpeechletResponse.newTellResponse(outputSpeech);
        }
        
         else if ("AMAZON.CancelIntent".equals(intentName)) {
            PlainTextOutputSpeech outputSpeech = new PlainTextOutputSpeech();
            outputSpeech.setText("Goodbye");

            return SpeechletResponse.newTellResponse(outputSpeech);
        }
        
         else {
            throw new SpeechletException("Invalid Intent");
        }
    }

    @Override
    public void onSessionEnded(final SessionEndedRequest request, final Session session)
            throws SpeechletException {
        log.info("onSessionEnded requestId={}, sessionId={}", request.getRequestId(),
                session.getSessionId());

        // any session cleanup logic would go here
    }

    /**
     * Function to handle the onLaunch skill behavior.
     * 
     * @return SpeechletResponse object with voice/card response to return to the user
     */
    private SpeechletResponse getWelcomeResponse() {
        String speechOutput = "Welcome to Asteroid Tracker. What day do you want events for?";

        return newAskResponse(speechOutput, false, INFORMATION_TEXT, false);
    }

    /**
     * Function to accept an intent containing a Day slot (date object) and return the Calendar
     * representation of that slot value. If the user provides a date, then use that, otherwise use
     * today. The date is in server time, not in the user's time zone. So "today" for the user may
     * actually be tomorrow.
     * 
     * @param intent
     *            the intent object containing the day slot
     * @return the Calendar representation of that date
     */
    private Calendar getCalendar(Intent intent) {
        Slot daySlot = intent.getSlot(SLOT_DAY);
        Date date;
        Calendar calendar = Calendar.getInstance();
        if (daySlot != null && daySlot.getValue() != null) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-d");
            try {
                date = dateFormat.parse(daySlot.getValue());
            } catch (ParseException e) {
                date = new Date();
            }
        } else {
            date = new Date();
        }
        calendar.setTime(date);
        return calendar;
    }

    private String buildSpeechOutputMarkup(String output)
    {
        return "<speak>" + output + "</speak>";
    }

    /**
     * Prepares the speech to reply to the user. Obtain events from NeoWs for the date specified
     * by the user (or for today's date, if no date is specified), and return those events in both
     * speech and SimpleCard format.
     * 
     * @param intent
     *            the intent object which contains the date slot
     * @param session
     *            the session object
     * @return SpeechletResponse object with voice/card response to return to the user
     */
    private SpeechletResponse handleFirstEventRequest(Intent intent, Session session) {
        Calendar calendar = getCalendar(intent);

        Date datetime = calendar.getTime();

        String month = MONTH_NAMES[calendar.get(Calendar.MONTH)];
        String date = Integer.toString(calendar.get(Calendar.DATE));

        String speechPrefixContent = "<p>For " + month + " " + date + "</p> ";
        String cardPrefixContent = "For " + month + " " + date + ", ";
        String cardTitle = "Asteroids on " + month + " " + date;

        ArrayList<String> events = getAsteroidInfo(new SimpleDateFormat("yyyy-MM-dd").format(datetime));
        String speechOutput = "";
        if (events.isEmpty()) 
        {
            speechOutput = "There is a problem connecting to the NASA A.P.I at this time."
                            + " Please try again later.";

            // Create the plain text output
            SsmlOutputSpeech outputSpeech = new SsmlOutputSpeech();
            outputSpeech.setSsml(buildSpeechOutputMarkup(speechOutput));

            return SpeechletResponse.newTellResponse(outputSpeech);
        } 
        
        else
         {
            StringBuilder speechOutputBuilder = new StringBuilder();
            speechOutputBuilder.append(speechPrefixContent);
            StringBuilder cardOutputBuilder = new StringBuilder();
            cardOutputBuilder.append(cardPrefixContent);
           
            for (int i = 0; i < PAGINATION_SIZE; i++) {
                if (events.size() > i)
                {
                speechOutputBuilder.append("<p>");
                speechOutputBuilder.append(events.get(i));
                speechOutputBuilder.append("</p> ");
                cardOutputBuilder.append(events.get(i));
                cardOutputBuilder.append("\n");
                }
        }
          
            speechOutputBuilder.append(" Wanna go deeper in history?");
            cardOutputBuilder.append(" Wanna go deeper in history?");
            speechOutput = speechOutputBuilder.toString();

            // Create the Simple card content.
            SimpleCard card = new SimpleCard();
            card.setTitle(cardTitle);
            card.setContent(cardOutputBuilder.toString());

            // After reading the first 3 events, set the count to 3 and add the events
            // to the session attributes
            session.setAttribute(SESSION_INDEX, PAGINATION_SIZE);
            session.setAttribute(SESSION_TEXT, events);

            SpeechletResponse response = newAskResponse(buildSpeechOutputMarkup(speechOutput), true, INFORMATION_TEXT, false);
            response.setCard(card);
            return response;
        }
    }

    /**
     * Prepares the speech to reply to the user. Obtains the list of events as well as the current
     * index from the session attributes. After getting the next set of events, increment the index
     * and store it back in session attributes. This allows us to obtain new events without making
     * repeated network calls, by storing values (events, index) during the interaction with the
     * user.
     * 
     * @param session
     *            object containing session attributes with events list and index
     * @return SpeechletResponse object with voice/card response to return to the user
     */
    private SpeechletResponse handleNextEventRequest(Session session) {
        String cardTitle = "More events on this day in history";
        ArrayList<String> events = (ArrayList<String>) session.getAttribute(SESSION_TEXT);
        int index = (Integer) session.getAttribute(SESSION_INDEX);
        String speechOutput = "";
        String cardOutput = "";
        if (events == null) {
            speechOutput = INFORMATION_TEXT;


        } else if (index >= events.size()) {
            speechOutput =
                    "There are no more events for this date. Try another date by saying, "
                            + " get events for February third.";
        } else {
            StringBuilder speechOutputBuilder = new StringBuilder();
            StringBuilder cardOutputBuilder = new StringBuilder();
            for (int i = 0; i < PAGINATION_SIZE && index < events.size(); i++) {
                speechOutputBuilder.append("<p>");
                speechOutputBuilder.append(events.get(index));
                speechOutputBuilder.append("</p> ");
                cardOutputBuilder.append(events.get(index));
                cardOutputBuilder.append(" ");
                index++;
            }
            if (index < events.size()) {
                speechOutputBuilder.append(" Want more events?");
                cardOutputBuilder.append(" Want more events?");
            }
            session.setAttribute(SESSION_INDEX, index);
            speechOutput = speechOutputBuilder.toString();
            cardOutput = cardOutputBuilder.toString();
        }

        // Create the Simple card content.
        SimpleCard card = new SimpleCard();
        card.setTitle(cardTitle);
        card.setContent(cardOutput.toString());

        SpeechletResponse response = newAskResponse(buildSpeechOutputMarkup(speechOutput), true, INFORMATION_TEXT, false);
        response.setCard(card);
        return response;
    }

    /**
     * Download JSON-formatted list of events from NeoWs API, for a defined day/date, and return a
     * String array of the events, with each event representing an element in the array.
     * 
     * @param month
     *            the month to get events for, example: April
     * @param date
     *            the date to get events for, example: 7
     * @return String array of events for that date, 1 event per element of the array
     */
    private ArrayList<String> getAsteroidInfo(String date)
    {
        InputStreamReader inputStream = null;
        BufferedReader bufferedReader = null;
        String text = "";
        try {
            String line;
            URL url = new URL(URL_PREFIX + URL_START_DATE + date + URL_END_DATE + date + URL_POSTFIX);
            inputStream = new InputStreamReader(url.openStream(), Charset.forName("US-ASCII"));
            bufferedReader = new BufferedReader(inputStream);
            StringBuilder builder = new StringBuilder();
            while ((line = bufferedReader.readLine()) != null) {
                builder.append(line);
            }
            text = builder.toString();
        } catch (IOException e) {
            // reset text variable to a blank string
            text = "";
        } finally {
            IOUtils.closeQuietly(inputStream);
            IOUtils.closeQuietly(bufferedReader);
        }
        return parseJson(text, date);
    }

    /**
     * Parse JSON-formatted list of events/births/deaths from Wikipedia, extract list of events and
     * split the events into a String array of individual events. Run Regex matchers to make the
     * list pretty by adding a comma after the year to add a pause, and by removing a unicode char.
     * 
     * @param text
     *            the JSON formatted list of events/births/deaths for a certain date
     * @return String array of events for that date, 1 event per element of the array
     */
    private ArrayList<String> parseJson(String text, String date) {
        ArrayList<String> events = new ArrayList<String>();

        if (text.isEmpty()) {
            return events;
        }
        JsonParser parser = new JsonParser();
        JsonObject object = parser.parse(text).getAsJsonObject();

        JsonArray asteroidArray = object.get("near_earth_objects").getAsJsonObject().get(date).getAsJsonArray();

        for (int i = 0; i < object.get("element_count").getAsInt(); i++) {
            JsonObject thisAsteroid = asteroidArray.get(i).getAsJsonObject();

            String asteroidName = thisAsteroid.get("name").getAsString();

            float absoluteMagnitude = thisAsteroid.get("absolute_magnitude_h").getAsFloat();

            JsonObject estimatedDiameterKilometers = thisAsteroid.get("estimated_diameter").getAsJsonObject().get("kilometers").getAsJsonObject();
            float minimumDiameter = estimatedDiameterKilometers.get("estimated_diameter_min").getAsFloat();
            float maximumDiameter = estimatedDiameterKilometers.get("estimated_diameter_max").getAsFloat();

            boolean isDangerousObject = thisAsteroid.get("is_potentially_hazardous_asteroid").getAsString() == "false";

            JsonObject closeApproachData= thisAsteroid.get("close_approach_data").getAsJsonArray().get(0).getAsJsonObject();
            float speed = closeApproachData.get("relative_velocity").getAsJsonObject().get("kilometers_per_hour").getAsFloat();
            Number missDistance = closeApproachData.get("miss_distance").getAsJsonObject().get("kilometers").getAsNumber();
            String orbitingBody = closeApproachData.get("orbiting_body").getAsString();

            DecimalFormat df = new DecimalFormat("#.##");

            String asteroidIdText = "Asteroid " + i + ", name is "  + asteroidName + ",";
            String magnitudeText = "The absolute magnitude is " + absoluteMagnitude;
            String sizeText = ", the estimated diameter is from " + df.format(minimumDiameter) + " to " + df.format(maximumDiameter) + " kilometers,";

            String dangerousnessText;
            if (isDangerousObject) {
                dangerousnessText = "This object is dangerous!";
            } else {
                dangerousnessText = "This object is not dangerous,";
            }

            String speedText = "It is traveling at " + df.format(speed) + " kilometers per hour";
            String distanceText = " at a distance of " + missDistance + " kilometers";
            String orbitingBodyText = " and is orbiting " + orbitingBody;

            String eventText = asteroidIdText + magnitudeText + sizeText + dangerousnessText + speedText + distanceText + orbitingBodyText;
            events.add(eventText);
        }

        return events;
    }

    /**
     * Wrapper for creating the Ask response from the input strings.
     *
     * @param stringOutput
     *            the output to be spoken
     * @param isOutputSsml
     *            whether the output text is of type SSML
     * @param repromptText
     *            the reprompt for if the user doesn't reply or is misunderstood.
     * @param isRepromptSsml
     *            whether the reprompt text is of type SSML
     * @return SpeechletResponse the speechlet response
     */
    private SpeechletResponse newAskResponse(String stringOutput, boolean isOutputSsml,
            String repromptText, boolean isRepromptSsml) {
        OutputSpeech outputSpeech, repromptOutputSpeech;
        if (isOutputSsml) {
            outputSpeech = new SsmlOutputSpeech();
            ((SsmlOutputSpeech) outputSpeech).setSsml(stringOutput);
        } else {
            outputSpeech = new PlainTextOutputSpeech();
            ((PlainTextOutputSpeech) outputSpeech).setText(stringOutput);
        }

        if (isRepromptSsml) {
            repromptOutputSpeech = new SsmlOutputSpeech();
            ((SsmlOutputSpeech) repromptOutputSpeech).setSsml(repromptText);
        } else {
            repromptOutputSpeech = new PlainTextOutputSpeech();
            ((PlainTextOutputSpeech) repromptOutputSpeech).setText(repromptText);
        }
        Reprompt reprompt = new Reprompt();
        reprompt.setOutputSpeech(repromptOutputSpeech);
        return SpeechletResponse.newAskResponse(outputSpeech, reprompt);
    }
}
