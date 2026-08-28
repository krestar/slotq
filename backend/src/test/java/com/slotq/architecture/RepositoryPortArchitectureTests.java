package com.slotq.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.slotq.auth.application.AccessControlRepository;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryPortArchitectureTests {

    @Test
    void productRepositoryPortsCannotExposeUnscopedAccess() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> repositoryPort : productRepositoryPorts()) {
            for (Method method : repositoryPort.getDeclaredMethods()) {
                if (!hasProductTenantScope(method)) {
                    violations.add(repositoryPort.getName() + "#" + method.getName());
                }
            }
        }
        assertThat(violations)
            .as("repository access must require an owned aggregate or trusted scope identifier")
            .isEmpty();
    }

    @Test
    void principalIdAloneCannotScopeAProductRepository() throws Exception {
        Method unsafeLookup = UnsafeCustomerRepository.class.getDeclaredMethod(
            "findByCustomerPrincipalId",
            PrincipalId.class
        );

        assertThat(hasProductTenantScope(unsafeLookup)).isFalse();
    }

    @Test
    void accessControlRepositoryUsesPrincipalWithAnExplicitTargetScope() {
        List<String> violations = new ArrayList<>();
        for (Method method : AccessControlRepository.class.getDeclaredMethods()) {
            boolean valid = method.getName().equals("registerPrincipal")
                ? hasParameter(method, PrincipalId.class) && method.getParameterCount() == 1
                : hasParameter(method, PrincipalId.class)
                    && (hasParameter(method, TenantId.class) || hasParameter(method, VenueId.class));
            if (!valid) {
                violations.add(method.getName());
            }
        }

        assertThat(violations)
            .as("access-control persistence must combine PrincipalId with its stored target scope")
            .isEmpty();
    }

    @Test
    void m1HasNoAiSystemOrSuperuserRole() {
        assertThat(TenantRole.values()).containsExactly(
            TenantRole.OWNER,
            TenantRole.MANAGER,
            TenantRole.STAFF
        );
    }

    private boolean hasProductTenantScope(Method method) {
        for (Class<?> parameter : method.getParameterTypes()) {
            if (parameter == TenantId.class
                || parameter == VenueId.class
                || parameter == Tenant.class
                || parameter == Venue.class) {
                return true;
            }
        }
        return false;
    }

    private boolean hasParameter(Method method, Class<?> parameterType) {
        return List.of(method.getParameterTypes()).contains(parameterType);
    }

    private List<Class<?>> productRepositoryPorts()
        throws IOException, URISyntaxException, ClassNotFoundException {
        return applicationRepositoryPorts().stream()
            .filter(repository -> repository != AccessControlRepository.class)
            .toList();
    }

    private List<Class<?>> applicationRepositoryPorts()
        throws IOException, URISyntaxException, ClassNotFoundException {
        Path packageRoot = Path.of(getClass().getClassLoader().getResource("com/slotq").toURI());
        List<Class<?>> repositories = new ArrayList<>();
        try (var paths = Files.walk(packageRoot)) {
            for (Path path : paths.filter(value -> value.toString().endsWith("Repository.class")).toList()) {
                String relative = packageRoot.relativize(path).toString()
                    .replace(path.getFileSystem().getSeparator(), ".")
                    .replaceAll("\\.class$", "");
                Class<?> candidate = Class.forName("com.slotq." + relative);
                if (candidate.isInterface() && candidate.getPackageName().endsWith(".application")) {
                    repositories.add(candidate);
                }
            }
        }
        return repositories;
    }

    private interface UnsafeCustomerRepository {

        Object findByCustomerPrincipalId(PrincipalId principalId);
    }
}
