/*
 * Copyright (c) 2025, WSO2 LLC. (http://www.wso2.com).
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v20.html
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     WSO2 LLC - support for WSO2 Micro Integrator Configuration
 */

package org.eclipse.lemminx.customservice.synapse.mediatorService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.customservice.synapse.connectors.ConnectorHolder;
import org.eclipse.lemminx.customservice.synapse.connectors.entity.Connection;
import org.eclipse.lemminx.customservice.synapse.connectors.entity.ConnectionParameter;
import org.eclipse.lemminx.customservice.synapse.connectors.entity.ConnectorAction;
import org.eclipse.lemminx.customservice.synapse.connectors.entity.OperationParameter;
import org.eclipse.lemminx.customservice.synapse.mediatorService.pojo.DocumentTextEdit;
import org.eclipse.lemminx.customservice.synapse.mediatorService.pojo.MCPToolResponse;
import org.eclipse.lemminx.customservice.synapse.mediatorService.pojo.SynapseConfigResponse;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.NewProjectResourceFinder;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.Resource;
import org.eclipse.lemminx.customservice.synapse.resourceFinder.pojo.ResourceResponse;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.SyntaxTreeGenerator;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.STNode;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.connector.Connector;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.connector.ai.AIAgent;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.connector.ai.AgentTool;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.mediator.Mediator;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.template.Template;
import org.eclipse.lemminx.customservice.synapse.syntaxTree.pojo.template.TemplateParameter;
import org.eclipse.lemminx.customservice.synapse.utils.ConfigFinder;
import org.eclipse.lemminx.customservice.synapse.utils.Constant;
import org.eclipse.lemminx.customservice.synapse.utils.UISchemaMapper;
import org.eclipse.lemminx.customservice.synapse.utils.Utils;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Document;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static org.eclipse.lemminx.customservice.synapse.utils.Utils.nodeToString;
import static org.eclipse.lemminx.utils.XMLPositionUtility.createRange;

public class AIConnectorHandler {

    private static final Logger LOGGER = Logger.getLogger(AIConnectorHandler.class.getName());
    private static final String AGENT_TOOL_TEMPLATE = "agentToolTemplate";
    private static final String TOOL_NAME = "toolName";
    private static final String TOOL_DESCRIPTION = "toolDescription";
    private static final String TOOL_RESULT_EXPRESSION = "toolResultExpression";
    private static final String RESULT_EXPRESSION = "resultExpression";
    private static final String SUPPORTS_AI_VALUES = "supportsAIValues";
    private static final String FUNCTION_PARAM_PREFIX = "${params.functionParams.";
    private static final String FROM_AI = "fromAI";
    private static final String AI_CONNECTOR_MUSTACHE_TEMPLATE_NAME = "AIConnector";
    private static final String MCP_MEDIATOR = "ai.mcpTools";
    private static final String MCP_CONNECTION = "mcpConnection";
    private static final String MCP_CONNECTIONS = "mcpConnections";
    private static final String CONFIG_KEY = "configKey";
    private static final String MCP_TOOLS_SELECTION = "mcpToolsSelection";
    private static final String IS_MCP = "isMCP";
    private static final String ERROR = "error";
    private static final String SERVER_URL = "mcpServerUrl";
    private static final String ACCESS_TOKEN = "bearerToken";
    private static final Path TEMPLATE_FOLDER_PATH = Path.of("src", "main", "wso2mi", "artifacts", "templates");
    Set<String> TOOL_EDIT_FIELDS = Set.of(TOOL_NAME, TOOL_DESCRIPTION, TOOL_RESULT_EXPRESSION, MCP_TOOLS_SELECTION);
    private final MediatorHandler mediatorHandler;
    private final String projectUri;
    private final Random randomGenerator = new Random();
    private static final List<String>
            CONNECTION_TYPES =
            List.of(Constant.LLM_CONFIG_KEY, Constant.MEMORY_CONFIG_KEY, Constant.EMBEDDING_CONFIG_KEY,
                    Constant.VECTOR_STORE_CONFIG_KEY);

    public AIConnectorHandler(MediatorHandler mediatorHandler, String projectUri) {

        this.mediatorHandler = mediatorHandler;
        this.projectUri = projectUri;
    }

    /**
     * Generates the AI agent operation configuration.
     *
     * @param node     syntax tree node for the current position if it is an edit operation, null otherwise
     * @param mediator mediator name
     * @param data     agent operation form data
     * @param range    range where the agent operation need to be added or updated
     * @return the generated configuration response
     */
    public SynapseConfigResponse generateAIConnectorConfig(STNode node, String mediator, Map<String, Object> data,
                                                           Range range) {

        ConnectorAction operation = mediatorHandler.getConnectorOperation(node, mediator);
        if (operation != null) {
            List<OperationParameter> parameters = operation.getParameters();
            Map<String, Object> connectorData = new HashMap<>();
            connectorData.put(Constant.TAG, operation.getTag());
            List<Map<String, String>> connections = addConnectionsDataForXML(data);
            connectorData.put(Constant.CONNECTIONS, connections);
            connectorData.put("supportTools", node instanceof AIAgent || Constant.AI_AGENT_TAG.equals(mediator));
            List<Object> parameterData = new ArrayList<>();
            for (OperationParameter parameter : parameters) {
                if (data.containsKey(parameter.getName())) {
                    Map<String, Object> dataValue =
                            mediatorHandler.processConnectorParameter(data.get(parameter.getName()));
                    if (dataValue != null && dataValue.containsKey(Constant.VALUE) &&
                            StringUtils.isNotEmpty(dataValue.get(Constant.VALUE).toString())) {
                        parameterData.add(Map.of(Constant.NAME, parameter.getName(), Constant.VALUE, dataValue));
                    }
                }
            }
            connectorData.put(Constant.PARAMETERS, parameterData);
            if (node instanceof AIAgent && ((AIAgent) node).getTools() != null) {
                List<Map<String, String>> tools = new ArrayList<>();
                for (AgentTool tool : ((AIAgent) node).getTools().getTools()) {
                    Map<String, String> toolData = new HashMap<>();
                    toolData.put(Constant.NAME, tool.getName());
                    toolData.put(Constant.TEMPLATE, tool.getTemplate());
                    toolData.put(Constant.RESULT_EXPRESSION, tool.getResultExpression());
                    toolData.put(Constant.DESCRIPTION, tool.getDescription());
                    tools.add(toolData);
                }
                connectorData.put(Constant.TOOLS, tools);
            }
            StringWriter writer = new StringWriter();
            String edit = mediatorHandler.getMustacheTemplate(AI_CONNECTOR_MUSTACHE_TEMPLATE_NAME)
                    .execute(writer, connectorData).toString();
            TextEdit textEdit = new TextEdit(range, edit);
            return new SynapseConfigResponse(textEdit);
        }
        return null;
    }

    private List<Map<String, String>> addConnectionsDataForXML(Map<String, Object> data) {

        List<Map<String, String>> connections = new ArrayList<>();
        for (String connectionType : CONNECTION_TYPES) {
            if (data.containsKey(connectionType)) {
                connections.add(
                        Map.of(Constant.NAME, connectionType, Constant.VALUE, data.get(connectionType).toString()));
            }
        }
        return connections;
    }

    /**
     * Generates the AI agent tool configuration.
     *
     * @param documentUri current document URI
     * @param range       range where the tool need to be added or updated
     * @param mediator    mediator name
     * @param data        the tool form data
     * @param dirtyFields list of fields that have been modified
     * @param isUpdate    true if it is an update operation, false otherwise
     * @return the generated configuration response
     * @throws IOException          if an error occurs while reading the current document/sequence template
     * @throws BadLocationException if the given range is invalid/ out of bounds
     */
    public SynapseConfigResponse generateAgentToolConfig(String documentUri, Range range, String mediator,
                                                         Map<String, Object> data, List<String> dirtyFields,
                                                         boolean isUpdate)
            throws IOException, BadLocationException {

        if (isUpdate) {
            return modifyAgentTool(documentUri, range, mediator, data, dirtyFields);
        } else {
            return addAIAgentTool(documentUri, range, mediator, data, dirtyFields);
        }
    }

    private SynapseConfigResponse addAIAgentTool(String documentUri, Range range, String mediator,
                                                 Map<String, Object> data, List<String> dirtyFields) throws BadLocationException, IOException {

        if (StringUtils.isEmpty(mediator)) {
            return null;
        }
        boolean isMCP = MCP_MEDIATOR.equals(mediator);
        String sequenceTemplateName = null;
        SynapseConfigResponse agentEditResponse = new SynapseConfigResponse();
        if (!isMCP) {
            String toolName = data.get(TOOL_NAME) != null ? data.get(TOOL_NAME).toString() : mediator;
            sequenceTemplateName = getSequenceTemplateName(toolName);

            String sequenceTemplatePath =
                    Path.of(projectUri).resolve(TEMPLATE_FOLDER_PATH).resolve(sequenceTemplateName + ".xml").toString();
            Map<String, Map<String, String>> templateParameters = new HashMap<>();

            processAIValues(data, templateParameters);

            // Replace overwrite body as false as we need the mediator response in the variable.
            data.replace(Constant.OVERWRITE_BODY, false);

            // Generate mediator/connector (tool) xml
            SynapseConfigResponse mediatorEdits =
                    mediatorHandler.generateSynapseConfig(sequenceTemplatePath, range, mediator, data, dirtyFields);
            if (mediatorEdits == null || mediatorEdits.getTextEdits() == null || mediatorEdits.getTextEdits().isEmpty()) {
                LOGGER.log(Level.SEVERE, "Error while generating mediator edits for the tool, {0}", mediator);
                return null;
            }

            // Generate sequence template xml
            String templateXml = generateSequenceTemplate(mediatorEdits, templateParameters, sequenceTemplateName, data);
            DocumentTextEdit sequenceTemplateEdit = new DocumentTextEdit(range, templateXml, sequenceTemplatePath);
            sequenceTemplateEdit.setCreateNewFile(true);
            agentEditResponse.addTextEdit(sequenceTemplateEdit);
        }

        data.put(IS_MCP, isMCP);
        if (data.containsKey(MCP_TOOLS_SELECTION) && data.get(MCP_TOOLS_SELECTION) instanceof List<?>) {
            String connectionName = StringUtils.EMPTY;
            if (data.containsKey(CONFIG_KEY) && data.get(CONFIG_KEY) instanceof String) {
                connectionName = data.get(CONFIG_KEY).toString();
            }

            List<String> mcpToolsSelection = (List<String>) data.get(MCP_TOOLS_SELECTION);

            String toolsXml = buildMcpToolsXml(mcpToolsSelection, connectionName, sequenceTemplateName);

            TextEdit toolsEditTextEdit = new DocumentTextEdit(range, toolsXml.toString(), documentUri);
            agentEditResponse.addTextEdit(toolsEditTextEdit);

            DOMDocument document = Utils.getDOMDocument(new File(documentUri));
            // Increment the character position by 1 to get the tool tag
            Position position = new Position(range.getStart().getLine(), range.getStart().getCharacter() + 1);
            DOMNode node = document.findNodeAt(document.offsetAt(position));
            DOMNode mcpMediatorNode = node.getParentNode();
            TextEdit textEdit = ensureMcpConnectionExists(document, mcpMediatorNode, connectionName, documentUri);
            agentEditResponse.addTextEdit(textEdit);
        } else {
            String toolXml = generateToolXml(data, sequenceTemplateName);
            TextEdit toolsEditTextEdit = new DocumentTextEdit(range, toolXml, documentUri);
            agentEditResponse.addTextEdit(toolsEditTextEdit);
        }

//        if (isMCP) {
//            DOMDocument document = Utils.getDOMDocument(new File(documentUri));
//            // Increment the character position by 1 to get the tool tag
//            Position position = new Position(range.getStart().getLine(), range.getStart().getCharacter() + 1);
//            DOMNode node = document.findNodeAt(document.offsetAt(position));
//            DOMNode mcpMediatorNode = node.getParentNode();
//            ensureMcpConnectionExists(document, mcpMediatorNode, )
//            agentEditResponse.addTextEdit(getMcpConnectionConfiguration(node, documentUri, data, document));
//        }
        return agentEditResponse;
    }

    private String buildMcpToolsXml(List<String> toolSelections, String connectionName, String sequenceTemplateName) {

        if (toolSelections == null || toolSelections.isEmpty()) {
            return StringUtils.EMPTY;
        }

        StringBuilder toolsXml = new StringBuilder();

        for (String toolSelection : toolSelections) {
            Map<String, Object> toolData = new HashMap<>();
            toolData.put(TOOL_NAME, toolSelection);
            toolData.put(MCP_CONNECTION, connectionName);

            String toolXml = generateToolXml(toolData, sequenceTemplateName);
            toolsXml.append(toolXml);
        }

        return toolsXml.toString();
    }

    /**
     * Processes the AI values in the data and updates the template parameters.
     * <p>
     * The field that expects value from AI will be replaced with an functional params, and corresponding template
     * parameter will be marked to be added to the sequence template.
     * </p>
     */
    private void processAIValues(Map<String, Object> data, Map<String, Map<String, String>> templateParameters) {

        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (isExpectingAIValue(entry.getValue())) {
                String parameterName = entry.getKey();
                String parameterDescription = extractParameterDescriptionFromFormValues(entry.getValue());

                Map<String, String> parameter = new HashMap<>();
                parameter.put(Constant.DESCRIPTION, parameterDescription);
                boolean isMandatory = entry.getValue() instanceof Map &&
                        ((Map) entry.getValue()).containsKey(Constant.IS_MANDATORY) &&
                        Boolean.TRUE.equals(((Map) entry.getValue()).get(Constant.IS_MANDATORY));
                parameter.put(Constant.IS_MANDATORY, String.valueOf(isMandatory));
                templateParameters.put(parameterName, parameter);

                Map<String, Object> expression = new HashMap<>();
                expression.put(Constant.VALUE, String.format("${params.functionParams.%s}", parameterName));
                expression.put(Constant.IS_EXPRESSION, true);
                data.put(parameterName, expression);
            }
        }
    }

    /**
     * Extracts the parameter description from the form values.
     *
     * @param data form value for the parameter
     * @return the parameter description
     */
    private String extractParameterDescriptionFromFormValues(Object data) {

        if (!(data instanceof Map)) {
            return StringUtils.EMPTY;
        }
        Map<?, ?> map = (Map<?, ?>) data;
        if (!map.containsKey(Constant.DESCRIPTION) || !(map.get(Constant.DESCRIPTION) instanceof Map<?, ?>)) {
            return StringUtils.EMPTY;
        }
        Object description = ((Map<?, ?>) map.get(Constant.DESCRIPTION)).get(Constant.CURRENT_VALUE);
        return description instanceof String ? description.toString() : StringUtils.EMPTY;
    }

    /**
     * Generates an XML representation for the specified tool.
     * <p>
     * The generated XML follows the format:
     * {@code <tool name="toolName" template="template" resultExpression="resultExpression"/>}
     * </p>
     */
    private String generateToolXml(Map<String, Object> data, String sequenceTemplateName) {

        Map<String, String> toolData = processToolData(data, sequenceTemplateName);
        StringWriter writer = new StringWriter();
        return mediatorHandler.getMustacheTemplate(Constant.TOOL).execute(writer, toolData).toString();
    }

    /**
     * Checks whether the field is expecting a value from AI.
     */
    private boolean isExpectingAIValue(Object value) {

        if (!(value instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return map.containsKey(FROM_AI) && Boolean.TRUE.equals(map.get(FROM_AI));
    }

    /**
     * Processes the data for generating the {@code <tool/>} element.
     */
    private Map<String, String> processToolData(Map<String, Object> data, String sequenceTemplateName) {

        Map<String, String> toolData = new HashMap<>();
        if (data.containsKey(TOOL_NAME)) {
            toolData.put(Constant.NAME, data.get(TOOL_NAME).toString());
        }
        if (!StringUtils.isEmpty(sequenceTemplateName)) {
            toolData.put(Constant.TEMPLATE, sequenceTemplateName);
        }
        if (data.containsKey(MCP_CONNECTION)) {
            toolData.put(MCP_CONNECTION, data.get(MCP_CONNECTION).toString());
            toolData.put(IS_MCP, Constant.TRUE);
        }
        if (data.containsKey(TOOL_RESULT_EXPRESSION) && data.get(TOOL_RESULT_EXPRESSION) instanceof Map<?, ?>) {
            Map<?, ?> expression = (Map<?, ?>) data.get(TOOL_RESULT_EXPRESSION);
            Object value = expression.get(Constant.VALUE);
            if (value instanceof String && StringUtils.isNotEmpty(value.toString())) {
                toolData.put(RESULT_EXPRESSION, String.format("${%s}", value));
            }
        } else if (data.containsKey(Constant.RESPONSE_VARIABLE)) {
            String variableName;
            if (data.get(Constant.RESPONSE_VARIABLE) instanceof Map<?, ?>) {
                variableName = ((Map<?, ?>) data.get(Constant.RESPONSE_VARIABLE)).get(Constant.VALUE).toString();
            } else {
                variableName = data.get(Constant.RESPONSE_VARIABLE).toString();
            }
            String resultExpression = String.format("${vars.%s.payload}", variableName);
            toolData.put(RESULT_EXPRESSION, resultExpression);
        }
        if (data.containsKey(TOOL_DESCRIPTION) && data.get(TOOL_DESCRIPTION) != null) {
            toolData.put(Constant.DESCRIPTION, data.get(TOOL_DESCRIPTION).toString());
        } else {
            toolData.put(Constant.DESCRIPTION, StringUtils.EMPTY);
        }
        return toolData;
    }

    /**
     * Generates a unique sequence template name for the newly added tool.
     */
    private String getSequenceTemplateName(String toolName) {

        String sequenceTemplateName = sanitizeToolName(toolName);
        NewProjectResourceFinder resourceFinder = new NewProjectResourceFinder();
        ResourceResponse response =
                resourceFinder.getAvailableResources(projectUri, Either.forLeft("sequenceTemplate"));
        if (response == null || response.getResources() == null) {
            return sequenceTemplateName + randomGenerator.nextInt(1000);
        }

        int i = 1;
        Set<String> existingNames =
                response.getResources().stream().map(Resource::getName).collect(Collectors.toSet());
        String newName = sequenceTemplateName;
        while (existingNames.contains(newName)) {
            newName = sequenceTemplateName + i;
            i++;
        }
        return newName;
    }

    public static String sanitizeToolName(String input) {

        if (input == null || input.isBlank()) {
            return Constant.TOOL;
        }

        String[] parts = input.trim().split("[\\s_\\-]+");
        StringBuilder result = new StringBuilder();

        for (String part : parts) {
            String cleaned = part.replaceAll("[^a-zA-Z0-9]", "");
            if (!cleaned.isEmpty()) {
                result.append(Character.toUpperCase(cleaned.charAt(0)));
                if (cleaned.length() > 1) {
                    result.append(cleaned.substring(1));
                }
            }
        }

        return result.toString();
    }

    /**
     * Generates the sequence template for the tool by adding the new mediator as a child in the sequence.
     */
    private String generateSequenceTemplate(SynapseConfigResponse mediatorEdits,
                                            Map<String, Map<String, String>> templateParameters,
                                            String sequenceTemplateName, Map<String, Object> data) {

        // There will be only one edit for a new mediator/connector. So, get the first edit from the list.
        TextEdit edit = mediatorEdits.getTextEdits().get(0);
        String mediatorXml = edit.getNewText();
        Map<String, Object> templateData = new HashMap<>();
        templateData.put(Constant.NAME, sequenceTemplateName);
        templateData.put(Constant.DESCRIPTION, data.get(TOOL_DESCRIPTION));
        List<Map<String, String>> parameters = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : templateParameters.entrySet()) {
            Map<String, String> parameter = new HashMap<>();
            parameter.put(Constant.NAME, entry.getKey());
            parameter.put(Constant.IS_MANDATORY, entry.getValue().get(Constant.IS_MANDATORY));
            parameter.put(Constant.DESCRIPTION, entry.getValue().get(Constant.DESCRIPTION));
            parameters.add(parameter);
        }
        templateData.put(Constant.PARAMETERS, parameters);
        templateData.put("mediatorXml", mediatorXml);
        StringWriter writer = new StringWriter();
        return mediatorHandler.getMustacheTemplate(AGENT_TOOL_TEMPLATE).execute(writer, templateData).toString();
    }

    /**
     * Retrieves the schema for the specified mediator to use as a tool.
     *
     * @param mediatorName the name of the mediator
     * @return the schema as a JsonObject
     */
    public JsonObject getToolSchema(String mediatorName) {

        boolean isConnector = mediatorName != null && mediatorName.contains(".");
        JsonObject schema = mediatorHandler.getUiSchema(mediatorName, null, null);
        if (schema != null && schema.has(Constant.OPERATION_NAME)) {
            String operationName = schema.get(Constant.OPERATION_NAME).getAsString();
            if (Constant.MCP_TOOLS.equals(operationName)) {
                return schema;
            }
        }

        JsonObject toolSchema = mediatorHandler.getUiSchema(Constant.TOOL, null, null).deepCopy();
        JsonObject mediatorSchema = null;
        if (schema != null) {
            mediatorSchema = schema.deepCopy();
            JsonArray elements = mediatorSchema.getAsJsonArray(Constant.ELEMENTS);
            JsonArray newElements = new JsonArray();

            // Remove the resultExpression field as it is not needed for connectors with response model
            if (isResponseModelAvailable(mediatorName)) {
                removeResultExpressionField(toolSchema);
            }

            if (isConnector) {
                ConnectorAction operation = ConnectorHolder.getInstance().getConnectorAction(mediatorName);
                String operationName = operation.getName();
                String operationDescription = operation.getDescription();

                // Update the tool name and description fields in schema with the connector operation description
                toolSchema.getAsJsonObject(Constant.VALUE).getAsJsonArray(Constant.ELEMENTS).get(0).getAsJsonObject()
                        .getAsJsonObject(Constant.VALUE).addProperty(Constant.CURRENT_VALUE, operationName);
                toolSchema.getAsJsonObject(Constant.VALUE).getAsJsonArray(Constant.ELEMENTS).get(1).getAsJsonObject()
                        .getAsJsonObject(Constant.VALUE).addProperty(Constant.CURRENT_VALUE, operationDescription);
            }
            newElements.add(toolSchema);

            markAIValueSupportedFields(elements, null);

            // Wrap the mediator/connector configuration fields with an attributeGroup to separate from tool fields
            JsonObject wrappedMediatorSchema = wrapMediatorSchema(elements, isConnector);
            newElements.add(wrappedMediatorSchema);
            mediatorSchema.add(Constant.ELEMENTS, newElements); // Replace the elements with the aggregated elements
        }
        return mediatorSchema;
    }

    private void removeResultExpressionField(JsonObject toolSchema) {

        toolSchema.get(Constant.VALUE).getAsJsonObject().getAsJsonArray(Constant.ELEMENTS)
                .remove(2); // index 2 is the resultExpression field. @{link ui-schemas/tool.json}
    }

    /**
     * Wraps the mediator/connector configuration elements into a new attribute group.
     */
    private JsonObject wrapMediatorSchema(JsonArray elements, boolean isConnector) {

        JsonObject wrapMediatorSchema = new JsonObject();
        new JsonObject();
        wrapMediatorSchema.addProperty("type", "attributeGroup");
        JsonObject wrapMediatorValueObj = new JsonObject();
        wrapMediatorValueObj.addProperty("groupName", StringUtils.EMPTY);
        wrapMediatorValueObj.add(Constant.ELEMENTS, elements); // Add the existing elements of the mediator
        wrapMediatorSchema.add(Constant.VALUE, wrapMediatorValueObj);
        return wrapMediatorSchema;
    }

    private boolean isResponseModelAvailable(String connectorTag) {

        if (StringUtils.isEmpty(connectorTag) || !connectorTag.contains(".")) {
            return false;
        }
        ConnectorAction operation = mediatorHandler.getConnectorOperation(null, connectorTag);
        if (operation != null) {
            return operation.isSupportsResponseModel();
        }
        return false;
    }

    /**
     * Enhances the schema by adding the `supportsAIValues` field.
     *
     * @param elements - The schema elements to modify.
     */
    private void markAIValueSupportedFields(JsonArray elements, Template template) {

        JsonArray descriptionElements = new JsonArray();
        for (JsonElement element : elements) {
            JsonObject elementObj = getElementObject(element);
            if (elementObj == null) {
                continue;
            }
            if (Constant.ATTRIBUTE.equals(elementObj.get(Constant.TYPE).getAsString())) {
                JsonObject valueObj = elementObj.getAsJsonObject(Constant.VALUE);
                String inputType = valueObj.get(Constant.INPUT_TYPE).getAsString();
                boolean isExpression = inputType.matches("^(?!.*combo.*)(?i).*expression$");
                boolean isExpressionTextArea = Constant.EXPRESSION_TEXT_AREA.equals(inputType);
                if (isExpression || isExpressionTextArea) {
                    if (isExpressionTextArea) {
                        // Convert the expressionTextArea to stringOrExpression to support AI values
                        valueObj.addProperty(Constant.INPUT_TYPE, "stringOrExpression");
                    }
                    valueObj.addProperty(SUPPORTS_AI_VALUES, true);
                    addParameterDescriptionField(valueObj, template);
                }
            } else if (Constant.ATTRIBUTE_GROUP.equals(elementObj.get(Constant.TYPE).getAsString())) {
                JsonObject elementObjValue = elementObj.getAsJsonObject(Constant.VALUE);
                if (Constant.OUTPUT.equalsIgnoreCase(elementObjValue.get("groupName").getAsString())) {
                    elementObjValue.addProperty("hidden", true);
                    continue;
                }
                markAIValueSupportedFields(elementObjValue.getAsJsonArray(Constant.ELEMENTS), template);
            }
        }
        elements.addAll(descriptionElements);
    }

    private JsonObject getElementObject(JsonElement element) {

        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject elementObj = element.getAsJsonObject();
        if (!elementObj.has(Constant.TYPE) || !elementObj.has(Constant.VALUE)) {
            return null;
        }
        return elementObj;
    }

    private JsonObject getParameterDescriptionObject(Template template, String parameterName, String helpTip) {

        JsonObject description = new JsonObject();
        description.addProperty(Constant.DEFAULT_VALUE, helpTip);
        if (template != null && template.hasParameter(parameterName)) {
            TemplateParameter parameter = template.getParameter(parameterName);
            description.addProperty(Constant.CURRENT_VALUE, parameter.getDescription());
        } else {
            description.addProperty(Constant.CURRENT_VALUE, helpTip);
        }
        return description;
    }

    private void addParameterDescriptionField(JsonObject valueObj, Template template) {

        String parameterName = valueObj.get(Constant.NAME).getAsString();
        String helpTip =
                valueObj.has(Constant.HELP_TIP) ? valueObj.get(Constant.HELP_TIP).getAsString() :
                        parameterName;
        boolean isRequired = valueObj.has(Constant.REQUIRED) && valueObj.get(Constant.REQUIRED).getAsBoolean();
        JsonObject descriptionElement = getParameterDescriptionObject(template, parameterName, helpTip);

        JsonElement currentValue = valueObj.get(Constant.CURRENT_VALUE);
        if (currentValue instanceof JsonObject) {
            ((JsonObject) currentValue).add(Constant.DESCRIPTION, descriptionElement);
            ((JsonObject) currentValue).addProperty(Constant.IS_MANDATORY, isRequired);
        } else if (!valueObj.get(Constant.INPUT_TYPE).getAsString().contains("boolean")) {
            valueObj.add(Constant.CURRENT_VALUE,
                    createNewValueObjectForToolField(currentValue, valueObj, descriptionElement, isRequired));
        }
    }

    private JsonElement createNewValueObjectForToolField(JsonElement currentValue, JsonObject valueObj,
                                                         JsonObject descriptionElement, boolean isRequired) {

        String inputType = valueObj.get(Constant.INPUT_TYPE).getAsString();
        JsonPrimitive value = getCurrentValueForToolField(currentValue, valueObj);
        JsonObject currentValueObj = new JsonObject();
        currentValueObj.add(Constant.VALUE, value);
        currentValueObj.addProperty(Constant.IS_EXPRESSION, Constant.EXPRESSION.equals(inputType));
        currentValueObj.addProperty(Constant.IS_MANDATORY, isRequired);
        currentValueObj.add(Constant.DESCRIPTION, descriptionElement);
        return currentValueObj;
    }

    private JsonPrimitive getCurrentValueForToolField(JsonElement currentValue, JsonObject valueObj) {

        JsonPrimitive value;
        JsonElement defaultValue = valueObj.get(Constant.DEFAULT_VALUE);
        if (currentValue != null) {
            value = currentValue.getAsJsonPrimitive();
        } else if (defaultValue != null) {
            value = defaultValue.getAsJsonPrimitive();
        } else {
            value = new JsonPrimitive(StringUtils.EMPTY);
        }
        return value;
    }

    /**
     * Retrieves the ui schema with current values for the agent tool at the given position.
     *
     * @param document current document
     * @param position position of the tool
     * @return the schema with current values
     */
    public JsonObject getToolSchemaWithValues(DOMDocument document, Position position) {

        try {
            Position toolPosition = new Position(position.getLine(), position.getCharacter() + 1);
            int offset = document.offsetAt(toolPosition);
            DOMNode node = document.findNodeAt(offset);
            if (node == null || !Constant.TOOL.equals(node.getNodeName())) {
                return null;
            }
            Template template = getSequenceTemplateForTool(node);
            if (isValidTool(template)) {
                Mediator mediator = template.getSequence().getMediatorList().get(0);
                JsonObject schema = mediatorHandler.getUISchemaForSTNode(mediator);
                markValueExpectedFromAI(schema, template);
                markAIValueSupportedFields(schema.getAsJsonArray(Constant.ELEMENTS), template);
                addToolConfigurations(schema, node, mediator);
                return schema;
            } else if (node.hasAttribute(Constant.TYPE)) {
                if (node instanceof DOMElement) {
                    return buildMcpMediatorUiSchema((DOMElement) node);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error while getting tool schema with values", e);
        }
        return null;
    }

    private JsonObject buildMcpMediatorUiSchema(DOMElement node) {

        JsonObject uiSchema = mediatorHandler.getUiSchema(MCP_MEDIATOR);
        JsonObject jsonObject = new JsonObject();

        String currentConnection = node.getAttribute(Constant.MCP_CONNECTION);
        jsonObject.addProperty(Constant.CONFIG_REF, currentConnection);

        JsonArray toolsArray = extractToolsForConnection(node, currentConnection);
        jsonObject.add(MCP_TOOLS_SELECTION, toolsArray);

        return UISchemaMapper.mapInputToUISchema(jsonObject, uiSchema);
    }

    private JsonArray extractToolsForConnection(DOMElement node, String connectionName) {

        JsonArray toolsArray = new JsonArray();
        Node toolsNode = node.getParentNode();

        if (toolsNode == null || toolsNode.getNodeType() != Node.ELEMENT_NODE) {
            return toolsArray;
        }

        NodeList toolNodes = toolsNode.getChildNodes();

        for (int i = 0; i < toolNodes.getLength(); i++) {
            Node child = toolNodes.item(i);

            if (child.getNodeType() == Node.ELEMENT_NODE
                    && Constant.TOOL.equals(child.getNodeName())) {

                DOMElement toolElement = (DOMElement) child;

                if (connectionName.equals(toolElement.getAttribute(Constant.MCP_CONNECTION))) {
                    toolsArray.add(toolElement.getAttribute(Constant.NAME));
                }
            }
        }

        return toolsArray;
    }

    /**
     * Adds tool configuration with the current values to the mediator/connector schema.
     */
    private void addToolConfigurations(JsonObject schema, DOMNode node, Mediator mediator) {

        JsonObject toolConfigSchema = mediatorHandler.getUiSchema(Constant.TOOL).deepCopy();
        Map<String, Object> toolData = new HashMap<>();
        toolData.put(TOOL_NAME, node.getAttribute(Constant.NAME));

        JsonObject expression = new JsonObject();
        expression.addProperty(Constant.IS_EXPRESSION, true);
        expression.addProperty(Constant.VALUE, node.getAttribute(RESULT_EXPRESSION));
        toolData.put(TOOL_RESULT_EXPRESSION, expression);

        toolData.put(TOOL_DESCRIPTION, node.getAttribute(Constant.DESCRIPTION));
        JsonArray elements = toolConfigSchema.getAsJsonObject(Constant.VALUE).getAsJsonArray(Constant.ELEMENTS);

        // Add the current values to the tool config schema
        for (JsonElement element : elements) {
            JsonObject valueObj = element.getAsJsonObject().getAsJsonObject(Constant.VALUE);
            Object currentValue = toolData.get(valueObj.get(Constant.NAME).getAsString());
            if (currentValue instanceof JsonObject) {
                valueObj.add(Constant.CURRENT_VALUE, (JsonObject) currentValue);
            } else {
                valueObj.addProperty(Constant.CURRENT_VALUE, currentValue.toString());
            }
        }

        JsonArray processedElements = new JsonArray();
        JsonArray mediatorSchemaElements = schema.getAsJsonArray(Constant.ELEMENTS);

        boolean isConnector = mediator instanceof Connector;

        // Remove the resultExpression field if the connector supports response model
        if (isResponseModelAvailable(mediator.getTag())) {
            removeResultExpressionField(toolConfigSchema);
        }
        processedElements.add(toolConfigSchema);

        JsonObject mediatorConfigurationSchema = wrapMediatorSchema(mediatorSchemaElements, isConnector);
        processedElements.add(mediatorConfigurationSchema);
        schema.add(Constant.ELEMENTS, processedElements);
    }

    /**
     * Processes the mediator schema to add the values expected from AI.
     * <p>
     * The existing mediator configurations has the fields that expects values from AI as a functional param. This will
     * be marked as a value expected from AI and added to the parameter description elements for those fields.
     * </p>
     */
    private void markValueExpectedFromAI(JsonObject schema, Template template) {

        JsonElement elements = schema.get(Constant.ELEMENTS);
        if (elements != null && elements.isJsonArray()) {
            JsonArray elementsArray = elements.getAsJsonArray();
            JsonArray parameterDescriptionElements = new JsonArray();
            for (JsonElement childElement : elementsArray) {
                if (Constant.ATTRIBUTE_GROUP.equals(childElement.getAsJsonObject().get(Constant.TYPE).getAsString())) {
                    markValueExpectedFromAI(
                            childElement.getAsJsonObject().getAsJsonObject(Constant.VALUE).getAsJsonObject(), template);
                } else if (Constant.ATTRIBUTE.equals(childElement.getAsJsonObject().get(Constant.TYPE).getAsString())) {
                    markAIValueForAttributeElement(childElement);
                }
            }
            elementsArray.addAll(parameterDescriptionElements);
        }
    }

    private void markAIValueForAttributeElement(JsonElement childElement) {

        JsonObject valueObj = childElement.getAsJsonObject().getAsJsonObject(Constant.VALUE);

        // Mark functional params as values expected from AI and add to the parameter description elements
        // for those fields.
        if (valueObj.get(Constant.CURRENT_VALUE) instanceof JsonObject &&
                valueObj.getAsJsonObject(Constant.CURRENT_VALUE).get(Constant.VALUE).getAsString()
                        .startsWith(FUNCTION_PARAM_PREFIX)) {

            JsonObject currentValue = new JsonObject();
            currentValue.addProperty(Constant.VALUE, FROM_AI);
            currentValue.addProperty(FROM_AI, true);
            currentValue.addProperty(Constant.IS_EXPRESSION, false);
            valueObj.add(Constant.CURRENT_VALUE, currentValue);
        }
    }

    /**
     * Checks whether the tool is valid.
     * <p>
     * The sequence template should have a sequence with at least one mediator to be eligible as a tool.
     * </p>
     */
    private boolean isValidTool(STNode stnode) {

        if (!(stnode instanceof Template)) {
            return false;
        }
        Template template = (Template) stnode;
        return template.getSequence() != null && template.getSequence().getMediatorList() != null &&
                !template.getSequence().getMediatorList().isEmpty();
    }

    /**
     * Returns the sequence template referenced in the tool definition.
     *
     * <p>
     * This method retrieves the {@code Template} defined in the {@code <tool template="key"/>}.
     * </p>
     *
     * @param node the tool node
     * @return the sequence template
     * @throws IOException if an error occurs while reading the template file
     */
    private Template getSequenceTemplateForTool(DOMNode node) throws IOException {

        String templateName = node.getAttribute(Constant.TEMPLATE);
        if (StringUtils.isNotEmpty(templateName)) {
            String templatePath = ConfigFinder.getTemplatePath(templateName, projectUri);
            if (StringUtils.isNotEmpty(templatePath)) {
                DOMDocument document = Utils.getDOMDocumentFromPath(templatePath);
                if (document != null) {
                    return (Template) SyntaxTreeGenerator.buildTree(document.getDocumentElement());
                }
            }
        }
        return null;
    }

    private SynapseConfigResponse modifyAgentTool(String documentUri, Range range, String mediator,
                                                  Map<String, Object> data, List<String> dirtyFields)
            throws IOException, BadLocationException {

        SynapseConfigResponse agentEditResponse = new SynapseConfigResponse();

        DOMDocument document = Utils.getDOMDocument(new File(documentUri));

        // Increment the character position by 1 to get the tool tag
        Position position = new Position(range.getStart().getLine(), range.getStart().getCharacter() + 1);
        DOMNode node = document.findNodeAt(document.offsetAt(position));
        if (node == null || !Constant.TOOL.equals(node.getNodeName())) {
            return null;
        }

        boolean isMCP = MCP_MEDIATOR.equals(mediator);

        String templateName = node.getAttribute(Constant.TEMPLATE);
        if (!isMCP && StringUtils.isEmpty(templateName)) {
            return null;
        }

        // Add tool tag edit
        boolean needToolEdit = dirtyFields.stream().anyMatch(TOOL_EDIT_FIELDS::contains) ||
                data.containsKey(Constant.RESPONSE_VARIABLE);
        if (needToolEdit) {
            if (data.containsKey(MCP_TOOLS_SELECTION) && data.get(MCP_TOOLS_SELECTION) instanceof List<?>) {

                DOMNode toolsNode = (DOMNode) node.getParentNode();
                if (toolsNode == null || !"tools".equals(toolsNode.getNodeName())) {
                    return null;
                }

                String targetConnection = node.getAttribute(Constant.MCP_CONNECTION);
                List<String> updatedTools = (List<String>) data.get(MCP_TOOLS_SELECTION);

                StringBuilder toolsXmlBuilder = new StringBuilder();
                toolsXmlBuilder.append("<tools>\n");

                NodeList children = toolsNode.getChildNodes();

                // Preserve tools from OTHER connections
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);

                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "tool".equals(child.getNodeName())) {

                        Element toolElem = (Element) child;
                        String connection = toolElem.getAttribute(Constant.MCP_CONNECTION);

                        if (!targetConnection.equals(connection)) {
                            toolsXmlBuilder.append("    ")
                                    .append(nodeToString(toolElem))
                                    .append("\n");
                        }
                    }
                }

                // Add updated tools for selected connection
                for (String tool : updatedTools) {
                    toolsXmlBuilder.append("    <tool ")
                            .append("name=\"").append(tool).append("\" ")
                            .append("type=\"mcp\" ")
                            .append("mcpConnection=\"").append(targetConnection).append("\" ")
                            .append("description=\"\"/>\n");
                }

                toolsXmlBuilder.append("</tools>");

                // Replace entire <tools> block
                Range toolsRange = createRange(toolsNode.getStart(), toolsNode.getEnd(), document);

                TextEdit edit = new DocumentTextEdit(
                        toolsRange,
                        toolsXmlBuilder.toString(),
                        documentUri
                );
                agentEditResponse.addTextEdit(edit);

                DOMNode agentNode = toolsNode.getParentNode();

                TextEdit mcpEdit = ensureMcpConnectionExists(
                        document,
                        agentNode,
                        targetConnection,
                        documentUri
                                                            );

                if (mcpEdit != null) {
                    agentEditResponse.addTextEdit(mcpEdit);
                }
            } else {
                Map<String, String> toolData = processToolData(data, templateName);
                StringWriter writer = new StringWriter();
                data.put(IS_MCP, isMCP);
                String toolsEdit = mediatorHandler.getMustacheTemplate(Constant.TOOL).execute(writer, toolData).toString();
                TextEdit toolsEditTextEdit = new DocumentTextEdit(range, toolsEdit, documentUri);
                agentEditResponse.addTextEdit(toolsEditTextEdit);
            }
        }

        boolean needTemplateEdit = !isMCP && dirtyFields.stream().anyMatch(field -> !TOOL_EDIT_FIELDS.contains(field));

        if (!needTemplateEdit) {
            return agentEditResponse;
        }

        // Generate mediator/connector (tool) edit for the sequence template
        String sequenceTemplatePath = ConfigFinder.getTemplatePath(templateName, projectUri);
        if (StringUtils.isEmpty(sequenceTemplatePath) && Files.notExists(Path.of(sequenceTemplatePath))) {
            return agentEditResponse;
        }
        DOMDocument sequenceTemplateDocument = Utils.getDOMDocumentFromPath(sequenceTemplatePath);
        STNode stNode = SyntaxTreeGenerator.buildTree(sequenceTemplateDocument.getDocumentElement());
        modifySequenceTemplate(stNode, data, dirtyFields, mediator, sequenceTemplatePath, agentEditResponse);
        return agentEditResponse;
    }

    private TextEdit ensureMcpConnectionExists(DOMDocument document,
                                               DOMNode agentNode,
                                               String connectionName,
                                               String documentUri) throws BadLocationException {

        DOMNode mcpConnectionsNode = null;

        // Find existing <mcpConnections>
        for (DOMNode child : agentNode.getChildren()) {
            if ("mcpConnections".equals(child.getNodeName())) {
                mcpConnectionsNode = child;
                break;
            }
        }

        // If <mcpConnections> exists
        if (mcpConnectionsNode != null) {

            boolean alreadyExists = false;

            for (DOMNode child : mcpConnectionsNode.getChildren()) {

                if ("mcpConfigKey".equals(child.getNodeName())) {

                    if (!child.getChildren().isEmpty()) {
                        String value = child.getChild(0).getTextContent();
                        if (value != null && connectionName.equals(value.trim())) {
                            alreadyExists = true;
                            break;
                        }
                    }
                }
            }

            if (alreadyExists) {
                return null; // nothing to edit
            }

            // Insert new <mcpConfigKey> before closing tag
            String newKeyXml = "    <mcpConfigKey>" + connectionName + "</mcpConfigKey>\n";

            int endOffset = mcpConnectionsNode.getEnd();
            String documentText = document.getText();

            int closingTagStart = documentText.lastIndexOf("</mcpConnections>", endOffset);

            Position insertPos = document.positionAt(closingTagStart);
            Range insertRange = new Range(insertPos, insertPos);

            return new DocumentTextEdit(insertRange, newKeyXml, documentUri);
        }

        // If <mcpConnections> does NOT exist → create full block

        String newBlock =
                "    <mcpConnections>\n" +
                        "        <mcpConfigKey>" + connectionName + "</mcpConfigKey>\n" +
                        "    </mcpConnections>\n";

        // Insert after </connections> if present
        DOMNode connectionsNode = null;
        for (DOMNode child : agentNode.getChildren()) {
            if ("connections".equals(child.getNodeName())) {
                connectionsNode = child;
                break;
            }
        }

        int insertOffset;

        if (connectionsNode != null) {
            // Insert after </connections>
            insertOffset = connectionsNode.getEnd();
        } else {

            // Insert right after opening <ai.agent ...>
            int agentStart = agentNode.getStart();
            String docText = document.getText();

            // Find the closing '>' of <ai.agent ...>
            int tagCloseOffset = docText.indexOf(">", agentStart);

            insertOffset = tagCloseOffset + 1;
        }

        Position insertPos = document.positionAt(insertOffset);
        Range insertRange = new Range(insertPos, insertPos);
        return new DocumentTextEdit(insertRange, newBlock, documentUri);
    }

    private void modifySequenceTemplate(STNode stNode, Map<String, Object> data, List<String> dirtyFields,
                                        String mediator, String sequenceTemplatePath,
                                        SynapseConfigResponse agentEditResponse) {

        if (!isValidTool(stNode)) {
            return;
        }
        Template template = (Template) stNode;
        Mediator toolMediator = template.getSequence().getMediatorList().get(0);
        Range toolMediatorRange = getSTNodeRange(toolMediator);

        Map<String, Map<String, String>> templateParameters = new HashMap<>();

        processAIValues(data, templateParameters);

        // Replace overwrite body as false as we need the mediator response in the variable.
        data.replace(Constant.OVERWRITE_BODY, false);

        SynapseConfigResponse mediatorEdits =
                mediatorHandler.generateSynapseConfig(sequenceTemplatePath, toolMediatorRange, mediator, data,
                        dirtyFields);
        String templateXml = generateSequenceTemplate(mediatorEdits, templateParameters, template.getName(), data);

        Range range = getSTNodeRange(template);
        range.setStart(new Position(0, 0));
        DocumentTextEdit sequenceTemplateEdit = new DocumentTextEdit(range, templateXml, sequenceTemplatePath);
        agentEditResponse.addTextEdit(sequenceTemplateEdit);

    }

    private Range getSTNodeRange(STNode stNode) {

        Range startTagRange = stNode.getRange().getStartTagRange();
        Range endTagRange = stNode.getRange().getEndTagRange();
        Position start = startTagRange.getStart();
        Position end = endTagRange != null ? endTagRange.getEnd() : startTagRange.getEnd();
        return new Range(start, end);
    }

    public MCPToolResponse fetchMcpTools(List<Connection> connections, String connectionName) {
        MCPToolResponse response = new MCPToolResponse();

        try {
            Connection connection = connections.stream()
                    .filter(c -> connectionName.equals(c.getName()))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException("Connection not found: " + connectionName)
                    );

            String serverUrl = null;
            String accessToken = null;
            for (ConnectionParameter param : connection.getParameters()) {
                if (SERVER_URL.equals(param.getName())) {
                    serverUrl = param.getValue();
                } else if (ACCESS_TOKEN.equals(param.getName())) {
                    accessToken = param.getValue();
                }
            }
            if (serverUrl == null || accessToken == null) {
                response.error = "ServerUrl or accessToken cannot be fetched from the connection parameters";
                return response;
            }

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(serverUrl))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "*/*")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{"
                            + "\"jsonrpc\":\"2.0\","
                            + "\"id\":1,"
                            + "\"method\":\"tools/list\""
                            + "}"
                    ))
                    .build();

            HttpResponse<String> httpResponse = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() != 200) {
                response.error = "Failed to fetch MCP tools. HTTP " + httpResponse.statusCode();
                return response;
            }

            BufferedReader reader = new BufferedReader(new StringReader(httpResponse.body()));
            String line;
            StringBuilder dataBuffer = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    break;
                }
                if (line.startsWith("data:")) {
                    dataBuffer.append(line.substring(5).trim());
                }
            }

            String responseJson = dataBuffer.toString();
            ObjectMapper mapper = new ObjectMapper();
            JsonNode dataJson = mapper.readTree(responseJson);

            if (dataJson.has(Constant.RESULT) && dataJson.get(Constant.RESULT).has(Constant.TOOLS)) {
                Map<String, String> tools = new HashMap<>();
                JsonNode toolsNode = dataJson.get(Constant.RESULT).get(Constant.TOOLS);
                if (!toolsNode.isArray()) {
                    response.error = "Invalid MCP response: tools is not an array";
                    return response;
                }

                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.has(Constant.NAME) ? toolNode.get(Constant.NAME).asText() : null;
                    if (toolName == null) {
                        continue;
                    }

                    String description = toolNode.has(Constant.DESCRIPTION)
                            ? toolNode.get(Constant.DESCRIPTION).asText()
                            : StringUtils.EMPTY;

                    tools.put(toolName, description);
                }

                response.tools = tools;

            } else if (dataJson.has(ERROR)) {
                response.error = dataJson.get(ERROR).toString();
            } else {
                response.error = "Unexpected MCP response";
            }

        } catch (Exception e) {
            response.error = e.getMessage();
        }

        return response;
    }

    private DocumentTextEdit getMcpConnectionConfiguration(DOMNode toolNode, String documentUri, Map<String, Object> data, DOMDocument document) throws BadLocationException {
        DOMNode toolsNode = toolNode.getParentNode();
        Set<String> mcpConnections = new HashSet<>();
        if (toolsNode != null && Constant.TOOLS.equals(toolsNode.getNodeName())) {

            NodeList children = toolsNode.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);

                if (child.getNodeType() == Node.ELEMENT_NODE
                        && Constant.TOOL.equals(child.getNodeName())) {

                    Element tool = (Element) child;
                    if (tool.hasAttribute(MCP_CONNECTION)) {
                        mcpConnections.add(tool.getAttribute(MCP_CONNECTION));
                    }
                }
            }
        }
        if (data.containsKey(CONFIG_KEY)) {
            mcpConnections.add(data.get(CONFIG_KEY).toString());
        }
        Range mcpConenctionRange;
        DOMNode mcpConnectionsNode = Utils.getChildNodeByName(toolsNode, MCP_CONNECTIONS);
        if (mcpConnectionsNode != null) {
            mcpConenctionRange = new Range(
                    document.positionAt(mcpConnectionsNode.getStart()),
                    document.positionAt(mcpConnectionsNode.getEnd())
            );
        } else {
            // Default to end of toolsNode if mcpConnectionsNode doesn't exist
            Position insertPosition = document.positionAt(toolsNode.getEnd());
            mcpConenctionRange = new Range(insertPosition, insertPosition);
        }
        StringBuilder mcpConnectionConfig = new StringBuilder();
        mcpConnectionConfig.append("<mcpConnections>\n");

        for (String key : mcpConnections) {
            mcpConnectionConfig.append("    <mcpConfigKey>")
                    .append(key)
                    .append("</mcpConfigKey>\n");
        }

        mcpConnectionConfig.append("</mcpConnections>");

        return new DocumentTextEdit(mcpConenctionRange, mcpConnectionConfig.toString(), documentUri);
    }

}
