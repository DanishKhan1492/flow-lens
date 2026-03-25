package com.dnlabz.flowlens.starter.config;

import org.springframework.aop.framework.autoproxy.AbstractAutoProxyCreator;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Automatically prevents two classes of Spring CGLIB proxy failures that arise
 * from third-party or framework beans FlowLens should never intercept.
 *
 * <h3>Case 1 — No visible constructors</h3>
 * <p>CGLIB requires at least one public/protected constructor to generate a
 * subclass (even when Objenesis is present, because the check happens during
 * class <em>generation</em>).  Classes like {@code HiveResourceTypeRegistry}
 * that only expose a private/package-private constructor fall into this bucket.
 *
 * <h3>Case 2 — Servlet filters</h3>
 * <p>When Spring CGLIB-proxies a {@code GenericFilterBean} / {@code Filter}
 * subclass via Objenesis it bypasses constructors <em>and</em> field
 * initializers.  {@code GenericFilterBean} initialises its {@code logger}
 * field inline ({@code = LogFactory.getLog(getClass())}); if that initializer
 * never runs the field stays {@code null} and Tomcat's {@code filter.init()}
 * call immediately throws an {@code NullPointerException}.
 *
 * <h3>Mechanism</h3>
 * <p>Both cases are handled the same way: when the
 * {@code AbstractAutoProxyCreator} bean is initialised, we pre-populate its
 * internal {@code advisedBeans} map with {@link Boolean#FALSE} for every
 * flagged bean name.  {@code wrapIfNecessary} checks this map first and
 * returns the raw instance without ever attempting CGLIB subclassing.
 *
 * <p>If the reflection patch cannot be applied a clear warning is logged with
 * the manual workaround ({@code spring.aop.proxy-target-class=false}).
 */
class CglibSafetyPostProcessor implements BeanFactoryPostProcessor, Ordered {

    private static final Logger LOG = Logger.getLogger(CglibSafetyPostProcessor.class.getName());

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE; // run after other BFPs; we just register a BPP
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Set<String> problematic = findProblematicBeans(beanFactory);
        if (!problematic.isEmpty()) {
            // Register our BPP programmatically so it runs before Spring's own BPPs
            beanFactory.addBeanPostProcessor(new SkipProxyBeanPostProcessor(problematic));
        }
    }

    // ── Scan ──────────────────────────────────────────────────────────────────

    private Set<String> findProblematicBeans(ConfigurableListableBeanFactory beanFactory) {
        Set<String> result = new LinkedHashSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();

        // Servlet filter supertypes — handle both javax and jakarta namespaces
        Class<?> javaxFilter   = loadSilently("javax.servlet.Filter", cl);
        Class<?> jakartaFilter = loadSilently("jakarta.servlet.Filter", cl);
        Class<?> genericFilter = loadSilently("org.springframework.web.filter.GenericFilterBean", cl);

        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            try {
                // getType(name, false) resolves the actual bean type WITHOUT eagerly
                // initializing the bean — critically, it inspects the @Bean factory
                // method's return type for factory-method-defined beans, unlike
                // BeanDefinition.getBeanClassName() which returns the @Configuration
                // class name and would miss those filter beans entirely.
                Class<?> cls = beanFactory.getType(beanName, false);
                if (cls == null) continue;
                if (cls.isInterface() || cls.isAnnotation() || cls.isEnum()
                        || Modifier.isAbstract(cls.getModifiers()) || cls.isSynthetic()) {
                    continue;
                }

                // Case 2 first: servlet filter — Objenesis bypasses field initializers
                // (GenericFilterBean.logger = LogFactory.getLog(getClass()) never runs),
                // leaving logger null and causing NPE in Tomcat's filter.init().
                if (isAssignableFromAny(cls, javaxFilter, jakartaFilter, genericFilter)) {
                    result.add(beanName);
                    LOG.fine("[FlowLens] Bean '" + beanName + "' (" + cls.getName()
                            + ") is a servlet Filter — CGLIB proxy disabled to "
                            + "prevent field-initializer skip via Objenesis.");
                    continue;
                }

                // Case 1: final class → CGLIB cannot subclass it at all
                if (Modifier.isFinal(cls.getModifiers())) {
                    result.add(beanName);
                    LOG.fine("[FlowLens] Bean '" + beanName + "' (" + cls.getName()
                            + ") is final — CGLIB proxy disabled automatically.");
                    continue;
                }

                // Case 2: no visible constructors → CGLIB class generation fails
                boolean hasVisible = false;
                for (Constructor<?> c : cls.getDeclaredConstructors()) {
                    int mod = c.getModifiers();
                    if (Modifier.isPublic(mod) || Modifier.isProtected(mod)) {
                        hasVisible = true;
                        break;
                    }
                }
                if (!hasVisible) {
                    result.add(beanName);
                    LOG.warning("[FlowLens] Bean '" + beanName + "' (" + cls.getName()
                            + ") has no visible constructors — CGLIB proxy disabled "
                            + "automatically to prevent startup failure.");
                }
            } catch (Exception | LinkageError ignored) {
                // Class not resolvable — skip silently
            }
        }
        return result;
    }

    private static boolean isAssignableFromAny(Class<?> cls, Class<?>... supertypes) {
        for (Class<?> sup : supertypes) {
            if (sup != null && sup.isAssignableFrom(cls)) return true;
        }
        return false;
    }

    private static Class<?> loadSilently(String name, ClassLoader cl) {
        try { return Class.forName(name, false, cl); } catch (Exception | LinkageError e) { return null; }
    }

    // ── Inner BPP ─────────────────────────────────────────────────────────────

    /**
     * Intercepts the {@link AbstractAutoProxyCreator} as it is initialised and
     * pre-populates its {@code advisedBeans} cache with {@link Boolean#FALSE}
     * for every problematic bean, so {@code wrapIfNecessary} returns the raw
     * instance without ever attempting CGLIB subclassing.
     */
    private static final class SkipProxyBeanPostProcessor implements BeanPostProcessor, Ordered {

        private final Set<String> beanNamesToSkip;
        private volatile boolean patched = false;

        SkipProxyBeanPostProcessor(Set<String> beanNamesToSkip) {
            this.beanNamesToSkip = beanNamesToSkip;
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
            // Patch the auto-proxy creator the first time we see it
            if (!patched && bean instanceof AbstractAutoProxyCreator apc) {
                patchAdvisedBeans(apc);
                patched = true;
            }
            return bean;
        }

        @SuppressWarnings("unchecked")
        private void patchAdvisedBeans(AbstractAutoProxyCreator apc) {
            try {
                Field field = AbstractAutoProxyCreator.class.getDeclaredField("advisedBeans");
                field.setAccessible(true);
                Map<Object, Boolean> advisedBeans = (Map<Object, Boolean>) field.get(apc);
                for (String name : beanNamesToSkip) {
                    advisedBeans.put(name, Boolean.FALSE);
                }
                LOG.info("[FlowLens] Auto-disabled CGLIB proxy for bean(s) with no visible "
                        + "constructors: " + beanNamesToSkip);
            } catch (Exception e) {
                // Reflection access denied (e.g. strict module system) — log the workaround
                LOG.warning("[FlowLens] Could not auto-patch CGLIB proxy issue for "
                        + beanNamesToSkip + ". Add the following to application.properties "
                        + "as a manual workaround:\n"
                        + "    spring.aop.proxy-target-class=false\n"
                        + "Cause: " + e);
            }
        }
    }
}
