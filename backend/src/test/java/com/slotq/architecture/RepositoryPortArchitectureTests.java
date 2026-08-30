package com.slotq.architecture;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.slotq.auth.application.AccessControlRepository;
import com.slotq.auth.domain.PrincipalId;
import com.slotq.auth.domain.TenantRole;
import com.slotq.booking.application.ReservationRepository;
import com.slotq.booking.application.SlotInventoryRepository;
import com.slotq.booking.domain.Reservation;
import com.slotq.booking.domain.SlotInventory;
import com.slotq.tenancy.domain.Tenant;
import com.slotq.tenancy.domain.TenantId;
import com.slotq.venue.application.ResourceRepository;
import com.slotq.venue.application.VenueRepository;
import com.slotq.venue.domain.Resource;
import com.slotq.venue.domain.Venue;
import com.slotq.venue.domain.VenueId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryPortArchitectureTests {

    private static final Set<Class<?>> ALLOWLISTED_AGGREGATE_WRITES = Set.of(
        Tenant.class,
        Venue.class,
        Resource.class,
        SlotInventory.class,
        Reservation.class
    );

    private static final Set<String> AGGREGATE_WRITE_METHODS = Set.of(
        "create",
        "save",
        "updateConfiguration"
    );

    @Test
    void scannerFindsMainProductRepositoryPorts() throws Exception {
        List<Class<?>> repositoryPorts = productRepositoryPorts();

        assertThat(repositoryPorts)
            .as("main application repository ports must be discovered")
            .isNotEmpty()
            .contains(
                VenueRepository.class,
                ResourceRepository.class,
                SlotInventoryRepository.class,
                ReservationRepository.class
            );
    }

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
    void unscopedLookupAndDeleteCannotUseAnAggregateAsScope() throws Exception {
        Method unsafeLookup = UnsafeAggregateRepository.class.getDeclaredMethod("find", Resource.class);
        Method unsafeDelete = UnsafeAggregateRepository.class.getDeclaredMethod("delete", Reservation.class);

        assertThat(hasProductTenantScope(unsafeLookup)).isFalse();
        assertThat(hasProductTenantScope(unsafeDelete)).isFalse();
    }

    @Test
    void allowlistedTenantOwnedAggregateWritesDoNotNeedASeparateScopeParameter() throws Exception {
        assertThat(hasProductTenantScope(
            ResourceRepository.class.getDeclaredMethod("save", Resource.class)
        )).isTrue();
        assertThat(hasProductTenantScope(
            SlotInventoryRepository.class.getDeclaredMethod("save", SlotInventory.class)
        )).isTrue();
        assertThat(hasProductTenantScope(
            ReservationRepository.class.getDeclaredMethod("save", Reservation.class)
        )).isTrue();
    }

    @Test
    void arbitraryDomainObjectsAreNotAggregateWriteExceptions() throws Exception {
        Method unsafeWrite = UnsafeAggregateRepository.class.getDeclaredMethod("save", Object.class);

        assertThat(hasProductTenantScope(unsafeWrite)).isFalse();
    }

    @Test
    void accessControlRepositoryUsesPrincipalWithAnExplicitTargetScope() {
        List<String> violations = new ArrayList<>();
        for (Method method : AccessControlRepository.class.getDeclaredMethods()) {
            boolean valid = method.getName().equals("registerPrincipal")
                ? hasParameter(method, PrincipalId.class) && method.getParameterCount() == 1
                : method.getName().equals("findActorsForPrincipalScopeDiscovery")
                    ? hasParameter(method, PrincipalId.class)
                        && method.getParameterCount() == 1
                        && method.getGenericReturnType().getTypeName().equals(
                            "java.util.List<com.slotq.auth.domain.ActorContext>"
                        )
                : hasParameter(method, PrincipalId.class)
                    && (hasParameter(method, TenantId.class) || hasParameter(method, VenueId.class));
            if (!valid) {
                violations.add(method.getName());
            }
        }

        assertThat(violations)
            .as("access-control persistence must use a target scope or the named self-scope discovery contract")
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
            if (parameter == TenantId.class || parameter == VenueId.class) {
                return true;
            }
        }
        return isAllowlistedAggregateWrite(method);
    }

    private boolean isAllowlistedAggregateWrite(Method method) {
        return AGGREGATE_WRITE_METHODS.contains(method.getName())
            && method.getParameterCount() == 1
            && ALLOWLISTED_AGGREGATE_WRITES.contains(method.getParameterTypes()[0]);
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
        Set<Class<?>> repositories = new LinkedHashSet<>();
        Enumeration<URL> packageRoots = getClass().getClassLoader().getResources("com/slotq");
        while (packageRoots.hasMoreElements()) {
            Path packageRoot = Path.of(packageRoots.nextElement().toURI());
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
        }
        return List.copyOf(repositories);
    }

    private interface UnsafeCustomerRepository {

        Object findByCustomerPrincipalId(PrincipalId principalId);
    }

    private interface UnsafeAggregateRepository {

        Object find(Resource resource);

        void delete(Reservation reservation);

        void save(Object arbitraryDomainObject);
    }
}
