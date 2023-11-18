/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.graphql.client;

import graphql.language.SourceLocation;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.ResultPath;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.DeserializationFeature;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.graphql.ResponseError;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.lang.Nullable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;


/**
 * Unit tests for {@link DefaultClientGraphQlResponse}.
 * @author Rossen Stoyanchev
 */
public class DefaultGraphQlClientResponseTests {

	private static final ObjectMapper mapper = new ObjectMapper();


	@Test
	void parsePath() throws Exception {
		testParsePath("");
		testParsePath("        \t  ");
		testParsePath("me.name", "me", "name");
		testParsePath("me.friends[1]", "me", "friends", 1);
		testParsePath("me.friends[1].name", "me", "friends", 1, "name");
		testParsePath(" me . name ", " me ", " name ");
	}

	private void testParsePath(String path, Object... expected) throws Exception {
		assertThat(getFieldOnDataResponse(path, "{}").getParsedPath()).containsExactly(expected);
	}

	@Test
	void parsePathInvalid() {
		testParseInvalidPath(".me");
		testParseInvalidPath("me..name");
		testParseInvalidPath("me.friends]");
		testParseInvalidPath("me.friends[[");
		testParseInvalidPath("me.friends[.");
		testParseInvalidPath("me.friends[]");
		testParseInvalidPath("me.friends[5]name");
		testParseInvalidPath("me.friends[5]]");
	}

	private void testParseInvalidPath(String path) {
		assertThatIllegalArgumentException().isThrownBy(() -> getFieldOnDataResponse(path, "{}")).withMessage("Invalid path: '" + path + "'");
	}

	@Test
	void fieldValue() throws Exception {

		// null "data"
		testFieldValue("", "null", null);
		testFieldValue("me", "null", null);

		// no such key or index
		testFieldValue("me", "{}", null); // "data" not null but no such key
		testFieldValue("me.friends", "{\"me\":{}}", null);
		testFieldValue("me.friends[0]", "{\"me\": {\"friends\": []}}", null);
		testFieldValue("me.friends[0]", "{\"me\": {\"friends\": []}}", null);

		// nest within map or list
		testFieldValue("me.name", "{\"me\":{\"name\":\"Luke\"}}", "Luke");
		testFieldValue("me.friends[1].name", "{\"me\": {\"friends\": [{\"name\": \"Luke\"}, {\"name\": \"Yoda\"}]}}", "Yoda");
	}

	private void testFieldValue(String path, String dataJson, @Nullable Object expected) throws Exception {
		Object value = getFieldOnDataResponse(path, dataJson).getValue();
		if (expected == null) {
			assertThat(value).isNull();
		}
		else {
			assertThat(value).isEqualTo(expected);
		}
	}

	@Test
	void fieldValueInvalidPath() {
		testFieldValueInvalidPath("me.name", "{\"me\": []}");
		testFieldValueInvalidPath("me.name", "{\"me\": \"string\"}");
		testFieldValueInvalidPath("me.friends[0]", "{\"me\": {\"friends\": {}}}");
		testFieldValueInvalidPath("me.friends[0]", "{\"me\": {\"friends\": {\"name\":\"Luke\"}}}");
	}

	private void testFieldValueInvalidPath(String path, String json) {
		assertThatIllegalArgumentException().isThrownBy(() -> getFieldOnDataResponse(path, json))
				.withMessageStartingWith("Invalid path");
	}

	@Test
	void fieldErrors() {

		String path = "me.friends";

		GraphQLError error0 = createError(null, "fail-me");
		GraphQLError error1 = createError("/me", "fail-me");
		GraphQLError error2 = createError("/me/friends", "fail-me-friends");
		GraphQLError error3 = createError("/me/friends[0]/name", "fail-me-friends-name");

		ClientResponseField field = getFieldOnErrorResponse(path, error0, error1, error2, error3);
		List<ResponseError> errors = field.getErrors();

		assertThat(errors).hasSize(3);
		assertThat(errors.get(0).getPath()).isEqualTo("me");
		assertThat(errors.get(1).getPath()).isEqualTo("me.friends");
		assertThat(errors.get(2).getPath()).isEqualTo("me.friends[0].name");
	}

	@Test
	void errorWithBigIntegerNumbers() throws IOException {

		String path = "me.friends";

		GraphQLError error0 = createError("/me", "fail-me", new SourceLocation(100, 100));

		ObjectMapper objectMapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
		String error0string = objectMapper.writeValueAsString(error0);
		Map<?, ?> error0Map = objectMapper.readValue(error0string, Map.class);

		List<?> list = List.of(error0Map);

		ClientGraphQlResponse response = createResponse(Collections.singletonMap("errors", list));
		ClientResponseField field =  response.field(path);

		List<ResponseError> errors = field.getErrors();

		assertThat(errors).hasSize(1);
		assertThat(errors.get(0).getPath()).isEqualTo("me");
		assertThat(errors.get(0).getLocations().get(0).getLine()).isEqualTo(100);
		assertThat(errors.get(0).getLocations().get(0).getColumn()).isEqualTo(100);
	}

	private GraphQLError createError(@Nullable String errorPath, String message, SourceLocation... locations) {
		GraphqlErrorBuilder<?> builder = GraphqlErrorBuilder.newError().message(message);
		if (errorPath != null) {
			builder = builder.path(ResultPath.parse(errorPath));
		}
		if (locations.length > 0) {
			builder = builder.locations(List.of(locations));
		}
		return builder.build();
	}

	private ClientResponseField getFieldOnDataResponse(String path, String dataJson) throws Exception {
		Map<?, ?> dataMap = mapper.readValue(dataJson, Map.class);
		ClientGraphQlResponse response = createResponse(Collections.singletonMap("data", dataMap));
		return response.field(path);
	}

	private ClientResponseField getFieldOnErrorResponse(String path, GraphQLError... errors) {
		List<?> list = Arrays.stream(errors).map(GraphQLError::toSpecification).toList();
		ClientGraphQlResponse response = createResponse(Collections.singletonMap("errors", list));
		return response.field(path);
	}

	private ClientGraphQlResponse createResponse(Map<String, Object> responseMap) {
		return new DefaultClientGraphQlResponse(
				new DefaultClientGraphQlRequest("{test}", null, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()),
				new ResponseMapGraphQlResponse(responseMap),
				new Jackson2JsonEncoder(), new Jackson2JsonDecoder());
	}

}
