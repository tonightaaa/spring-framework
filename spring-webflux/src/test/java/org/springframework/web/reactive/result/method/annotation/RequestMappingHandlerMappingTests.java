/*
 * Copyright 2002-2024 the original author or authors.
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

package org.springframework.web.reactive.result.method.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.core.annotation.AliasFor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.reactive.result.condition.ConsumesRequestCondition;
import org.springframework.web.reactive.result.condition.MediaTypeExpression;
import org.springframework.web.reactive.result.condition.PatternsRequestCondition;
import org.springframework.web.reactive.result.method.RequestMappingInfo;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.annotation.PutExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link RequestMappingHandlerMapping}.
 *
 * @author Rossen Stoyanchev
 * @author Olga Maciaszek-Sharma
 * @author Sam Brannen
 */
class RequestMappingHandlerMappingTests {

	private final StaticWebApplicationContext wac = new StaticWebApplicationContext();

	private final RequestMappingHandlerMapping handlerMapping = new RequestMappingHandlerMapping();


	@BeforeEach
	void setup() {
		this.handlerMapping.setApplicationContext(wac);
	}


	@Test
	void resolveEmbeddedValuesInPatterns() {
		this.handlerMapping.setEmbeddedValueResolver(value -> "/${pattern}/bar".equals(value) ? "/foo/bar" : value);

		String[] patterns = new String[] { "/foo", "/${pattern}/bar" };
		String[] result = this.handlerMapping.resolveEmbeddedValuesInPatterns(patterns);

		assertThat(result).isEqualTo(new String[] { "/foo", "/foo/bar" });
	}

	@Test
	void pathPrefix() throws Exception {
		this.handlerMapping.setEmbeddedValueResolver(value -> "/${prefix}".equals(value) ? "/api" : value);
		this.handlerMapping.setPathPrefixes(Collections.singletonMap(
				"/${prefix}", HandlerTypePredicate.forAnnotation(RestController.class)));

		Method method = UserController.class.getMethod("getUser");
		RequestMappingInfo info = this.handlerMapping.getMappingForMethod(method, UserController.class);

		assertThat(info).isNotNull();
		assertThat(info.getPatternsCondition().getPatterns()).isEqualTo(Collections.singleton(new PathPatternParser().parse("/api/user/{id}")));
	}

	@Test
	void resolveRequestMappingViaComposedAnnotation() {
		RequestMappingInfo info = assertComposedAnnotationMapping("postJson", "/postJson", RequestMethod.POST);

		assertThat(info.getConsumesCondition().getConsumableMediaTypes().iterator().next().toString()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		assertThat(info.getProducesCondition().getProducibleMediaTypes().iterator().next().toString()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
	}

	@Test // SPR-14988
	void getMappingOverridesConsumesFromTypeLevelAnnotation() {
		RequestMappingInfo requestMappingInfo = assertComposedAnnotationMapping(RequestMethod.POST);

		ConsumesRequestCondition condition = requestMappingInfo.getConsumesCondition();
		assertThat(condition.getConsumableMediaTypes()).isEqualTo(Collections.singleton(MediaType.APPLICATION_XML));
	}

	@Test // gh-22010
	void consumesWithOptionalRequestBody() {
		this.wac.registerSingleton("testController", ComposedAnnotationController.class);
		this.wac.refresh();
		this.handlerMapping.afterPropertiesSet();
		RequestMappingInfo info = this.handlerMapping.getHandlerMethods().keySet().stream()
				.filter(i -> {
					PatternsRequestCondition condition = i.getPatternsCondition();
					return condition.getPatterns().iterator().next().getPatternString().equals("/post");
				})
				.findFirst()
				.orElseThrow(() -> new AssertionError("No /post"));

		assertThat(info.getConsumesCondition().isBodyRequired()).isFalse();
	}

	@Test
	void getMapping() {
		assertComposedAnnotationMapping(RequestMethod.GET);
	}

	@Test
	void postMapping() {
		assertComposedAnnotationMapping(RequestMethod.POST);
	}

	@Test
	void putMapping() {
		assertComposedAnnotationMapping(RequestMethod.PUT);
	}

	@Test
	void deleteMapping() {
		assertComposedAnnotationMapping(RequestMethod.DELETE);
	}

	@Test
	void patchMapping() {
		assertComposedAnnotationMapping(RequestMethod.PATCH);
	}

	@Test  // gh-32049
	void httpExchangeWithMultipleAnnotationsAtClassLevel() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		Class<?> controllerClass = MultipleClassLevelAnnotationsHttpExchangeController.class;
		Method method = controllerClass.getDeclaredMethod("post");

		assertThatIllegalStateException()
				.isThrownBy(() -> this.handlerMapping.getMappingForMethod(method, controllerClass))
				.withMessageContainingAll(
					"Multiple @HttpExchange annotations found on " + controllerClass,
					"@" + HttpExchange.class.getName(),
					"@" + ExtraHttpExchange.class.getName()
				);
	}

	@Test  // gh-32049
	void httpExchangeWithMultipleAnnotationsAtMethodLevel() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		Class<?> controllerClass = MultipleMethodLevelAnnotationsHttpExchangeController.class;
		Method method = controllerClass.getDeclaredMethod("post");

		assertThatIllegalStateException()
				.isThrownBy(() -> this.handlerMapping.getMappingForMethod(method, controllerClass))
				.withMessageContainingAll(
					"Multiple @HttpExchange annotations found on " + method,
					"@" + PostExchange.class.getName(),
					"@" + PutExchange.class.getName()
				);
	}

	@Test  // gh-32065
	void httpExchangeWithMixedAnnotationsAtClassLevel() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		Class<?> controllerClass = MixedClassLevelAnnotationsController.class;
		Method method = controllerClass.getDeclaredMethod("post");

		assertThatIllegalStateException()
				.isThrownBy(() -> this.handlerMapping.getMappingForMethod(method, controllerClass))
				.withMessageContainingAll(
					controllerClass.getName(),
					"is annotated with @RequestMapping and @HttpExchange annotations, but only one is allowed:",
					"@" + RequestMapping.class.getName(),
					"@" + HttpExchange.class.getName()
				);
	}

	@Test  // gh-32065
	void httpExchangeWithMixedAnnotationsAtMethodLevel() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		Class<?> controllerClass = MixedMethodLevelAnnotationsController.class;
		Method method = controllerClass.getDeclaredMethod("post");

		assertThatIllegalStateException()
				.isThrownBy(() -> this.handlerMapping.getMappingForMethod(method, controllerClass))
				.withMessageContainingAll(
					method.toString(),
					"is annotated with @RequestMapping and @HttpExchange annotations, but only one is allowed:",
					"@" + PostMapping.class.getName(),
					"@" + PostExchange.class.getName()
				);
	}

	@SuppressWarnings("DataFlowIssue")
	@Test
	void httpExchangeWithDefaultValues() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		RequestMappingInfo mappingInfo = this.handlerMapping.getMappingForMethod(
				HttpExchangeController.class.getMethod("defaultValuesExchange"),
				HttpExchangeController.class);

		assertThat(mappingInfo.getPatternsCondition().getPatterns())
				.extracting(PathPattern::toString)
				.containsOnly("/exchange");

		assertThat(mappingInfo.getMethodsCondition().getMethods()).isEmpty();
		assertThat(mappingInfo.getParamsCondition().getExpressions()).isEmpty();
		assertThat(mappingInfo.getHeadersCondition().getExpressions()).isEmpty();
		assertThat(mappingInfo.getConsumesCondition().getExpressions()).isEmpty();
		assertThat(mappingInfo.getProducesCondition().getExpressions()).isEmpty();
	}

	@SuppressWarnings("DataFlowIssue")
	@Test
	void httpExchangeWithCustomValues() throws NoSuchMethodException {
		this.handlerMapping.afterPropertiesSet();

		RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
		mapping.setApplicationContext(new StaticWebApplicationContext());
		mapping.afterPropertiesSet();

		RequestMappingInfo mappingInfo = mapping.getMappingForMethod(
				HttpExchangeController.class.getMethod("customValuesExchange"),
				HttpExchangeController.class);

		assertThat(mappingInfo.getPatternsCondition().getPatterns())
				.extracting(PathPattern::toString)
				.containsOnly("/exchange/custom");

		assertThat(mappingInfo.getMethodsCondition().getMethods()).containsOnly(RequestMethod.POST);
		assertThat(mappingInfo.getParamsCondition().getExpressions()).isEmpty();
		assertThat(mappingInfo.getHeadersCondition().getExpressions()).isEmpty();

		assertThat(mappingInfo.getConsumesCondition().getExpressions())
				.extracting(MediaTypeExpression::getMediaType)
				.containsOnly(MediaType.APPLICATION_JSON);

		assertThat(mappingInfo.getProducesCondition().getExpressions())
				.extracting(MediaTypeExpression::getMediaType)
				.containsOnly(MediaType.valueOf("text/plain;charset=UTF-8"));
	}

	private RequestMappingInfo assertComposedAnnotationMapping(RequestMethod requestMethod) {
		String methodName = requestMethod.name().toLowerCase();
		String path = "/" + methodName;
		return assertComposedAnnotationMapping(methodName, path, requestMethod);
	}

	private RequestMappingInfo assertComposedAnnotationMapping(
			String methodName, String path, RequestMethod requestMethod) {

		Class<?> clazz = ComposedAnnotationController.class;
		Method method = ClassUtils.getMethod(clazz, methodName, (Class<?>[]) null);
		RequestMappingInfo info = this.handlerMapping.getMappingForMethod(method, clazz);

		assertThat(info).isNotNull();

		Set<PathPattern> paths = info.getPatternsCondition().getPatterns();
		assertThat(paths).hasSize(1);
		assertThat(paths.iterator().next().getPatternString()).isEqualTo(path);

		Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
		assertThat(methods).containsExactly(requestMethod);

		return info;
	}


	@Controller @SuppressWarnings("unused")
	// gh-31962: The presence of multiple @RequestMappings is intentional.
	@RequestMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	@ExtraRequestMapping
	static class ComposedAnnotationController {

		@RequestMapping
		public void handle() {
		}

		@PostJson("/postJson")
		public void postJson() {
		}

		@GetMapping("/get")
		public void get() {
		}

		@PostMapping(path = "/post", consumes = MediaType.APPLICATION_XML_VALUE)
		public void post(@RequestBody(required = false) Foo foo) {
		}

		// gh-31962: The presence of multiple @RequestMappings is intentional.
		@PatchMapping("/put")
		@RequestMapping(path = "/put", method = RequestMethod.PUT) // local @RequestMapping overrides meta-annotations
		@PostMapping("/put")
		public void put() {
		}

		@DeleteMapping("/delete")
		public void delete() {
		}

		@PatchMapping("/patch")
		public void patch() {
		}
	}

	private static class Foo {
	}


	@RequestMapping
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@interface ExtraRequestMapping {
	}


	@RequestMapping(method = RequestMethod.POST,
			produces = MediaType.APPLICATION_JSON_VALUE,
			consumes = MediaType.APPLICATION_JSON_VALUE)
	@Target(ElementType.METHOD)
	@Retention(RetentionPolicy.RUNTIME)
	@interface PostJson {

		@AliasFor(annotation = RequestMapping.class, attribute = "path") @SuppressWarnings("unused")
		String[] value() default {};
	}


	@RestController
	@RequestMapping("/user")
	static class UserController {

		@GetMapping("/{id}")
		public Principal getUser() {
			return mock();
		}
	}


	@RestController
	@HttpExchange("/exchange")
	static class HttpExchangeController {

		@HttpExchange
		public void defaultValuesExchange() {}

		@PostExchange(url = "/custom", contentType = "application/json", accept = "text/plain;charset=UTF-8")
		public void customValuesExchange(){}
	}

	@HttpExchange("/exchange")
	@ExtraHttpExchange
	static class MultipleClassLevelAnnotationsHttpExchangeController {

		@PostExchange("/post")
		void post() {}
	}


	static class MultipleMethodLevelAnnotationsHttpExchangeController {

		@PostExchange("/post")
		@PutExchange("/post")
		void post() {}
	}


	@Controller
	@RequestMapping("/api")
	@HttpExchange("/api")
	static class MixedClassLevelAnnotationsController {

		@PostExchange("/post")
		void post() {}
	}


	@Controller
	@RequestMapping("/api")
	static class MixedMethodLevelAnnotationsController {

		@PostMapping("/post")
		@PostExchange("/post")
		void post() {}
	}


	@HttpExchange
	@Target(ElementType.TYPE)
	@Retention(RetentionPolicy.RUNTIME)
	@interface ExtraHttpExchange {
	}

}
