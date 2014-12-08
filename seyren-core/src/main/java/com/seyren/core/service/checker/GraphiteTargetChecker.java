/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seyren.core.service.checker;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Optional;
import com.seyren.core.domain.Check;
import com.seyren.core.exception.InvalidGraphiteValueException;
import com.seyren.core.util.graphite.GraphiteHttpClient;
import com.seyren.core.util.graphite.GraphiteReadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Named
public class GraphiteTargetChecker implements TargetChecker {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphiteTargetChecker.class);
    
    private final GraphiteHttpClient graphiteHttpClient;
    
    @Inject
    public GraphiteTargetChecker(GraphiteHttpClient graphiteHttpClient) {
        this.graphiteHttpClient = graphiteHttpClient;
    }
    
    @Override
    public Map<String, Optional<BigDecimal>> check(Check check) throws Exception {
        Map<String, Optional<BigDecimal>> targetValues = new HashMap<String, Optional<BigDecimal>>();
        boolean isHigherWorse = ValueChecker.isTheValueBeingHighWorse(check.getWarn(), check.getError());

        try {
            JsonNode node = graphiteHttpClient.getTargetJson(check.getTarget(), check.getFrom(), check.getUntil());
            for (JsonNode metric : node) {
                String target = metric.path("target").asText();
                try {
                    BigDecimal value = getWorstValue(metric, isHigherWorse);
                    targetValues.put(target, Optional.of(value));
                } catch (InvalidGraphiteValueException e) {
                    // Silence these - we don't know what's causing Graphite to return null values
                    LOGGER.warn("{} failed to read from Graphite", check.getName(), e);
                    targetValues.put(target, Optional.<BigDecimal> absent());
                }
            }
        } catch (GraphiteReadException e) {
            LOGGER.warn(check.getName() + " failed to read from Graphite", e);
        }
        
        return targetValues;
    }
    
    /**
     * Loop through the datapoints in reverse order until we find the latest non-null value
     */
    private BigDecimal getLatestValue(JsonNode node) throws Exception {
        JsonNode datapoints = node.get("datapoints");

        for (int i = datapoints.size() - 1; i >= 0; i--) {
            String value = datapoints.get(i).get(0).asText();
            if (!value.equals("null")) {
                return new BigDecimal(value);
            }
        }
        
        LOGGER.warn("{}", node);
        throw new InvalidGraphiteValueException("Could not find a valid datapoint for target: " + node.get("target"));
    }

    /**
     * Loop through the datapoints and find the worst value relative to alerts and thresholds available
     */
    private BigDecimal getWorstValue(JsonNode node, Boolean isHigherWorse) throws Exception {
        JsonNode datapoints = node.get("datapoints");
        BigDecimal worst = null;

        for (int i = 0; i < datapoints.size(); i++) {
            String value = datapoints.get(i).get(0).asText();
            if(!value.equals("null")) {
                if(worst == null) {
                    worst = new BigDecimal(value);
                } else {
                    worst = compareWorstValue(new BigDecimal(value), worst, isHigherWorse);
                }
            }
        }

        if(worst == null) {
            LOGGER.warn("{}", node);
            throw new InvalidGraphiteValueException("Could not find a valid datapoint for target: " + node.get("target"));
        } else {
            return worst;
        }
    }

    private BigDecimal compareWorstValue(BigDecimal prevWorst, BigDecimal alternate, boolean isHigherWorse) {
        if(isHigherWorse) {
            return prevWorst.max(alternate);
        }
        return prevWorst.min(alternate);
    }
}
