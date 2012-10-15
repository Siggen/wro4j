/**
 * Copyright Alex Objelean
 */
package ro.isdc.wro.model.group.processor;

import java.lang.ref.SoftReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.config.Context;
import ro.isdc.wro.model.group.Inject;
import ro.isdc.wro.model.group.processor.InjectorBuilder.InjectorObjectFactory;
import ro.isdc.wro.util.ObjectDecorator;


/**
 * Injector scans some object fields and checks if a value can be provided to a field; Injector will ignore all non-null
 * fields.
 *
 * @author Alex Objelean
 * @created 20 Nov 2010
 */
public final class Injector {
  private static final Logger LOG = LoggerFactory.getLogger(Injector.class);
  private final Map<Class<?>, Object> map;
  /**
   * Keep reference of already injected object to avoid recursion.
   */
  final SoftReference<Set<Object>> injectedObjects = new SoftReference<Set<Object>>(new HashSet<Object>()) {
    @Override
    public Set<Object> get() {
      Set<Object> initial = super.get();
      if (initial == null) {
        initial = new HashSet<Object>();
      }
      return initial;
    };
  };

  /**
   * Mapping of classes to be annotated and the corresponding injected object.
   */
  Injector(final Map<Class<?>, Object> map) {
    Validate.notNull(map);
    this.map = map;
  }

  /**
   * Scans the object and inject the supported values into the fields having @Inject annotation present.
   *
   * @param object
   *          {@link Object} which will be scanned for @Inject annotation presence.
   */
  public void inject(final Object object) {
    Validate.notNull(object);
    processInjectAnnotation(object);
  }

  /**
   * Check for each field from the passed object if @Inject annotation is present & inject the required field if
   * supported, otherwise warns about invalid usage.
   *
   * @param object
   *          to check for annotation presence.
   */
  private void processInjectAnnotation(final Object object) {
    //TODO find a better way to avoid recursion
    if (injectedObjects.get().contains(object)) {
      return;
    }
    injectedObjects.get().add(object);
    LOG.debug("injecting: {}", object);

    try {
      final Collection<Field> fields = getAllFields(object);
      for (final Field field : fields) {
        if (field.isAnnotationPresent(Inject.class)) {
          if (!acceptAnnotatedField(object, field)) {
            final String message = "@Inject cannot be applied to field of type: " + field.getType();
            LOG.error(message + ". Supported types are: {}", map.keySet());
            throw new WroRuntimeException(message);
          }
        }
      }
      // handle special cases like decorators. Perform recursive injection
      if (object instanceof ObjectDecorator) {
        processInjectAnnotation(((ObjectDecorator<?>) object).getDecoratedObject());
      }
    } catch (final Exception e) {
      LOG.error("Error while scanning @Inject annotation", e);
      throw new WroRuntimeException("Exception while trying to process @Inject annotation", e);
    }
  }

  /**
   * Return all fields for given object, also those from the super classes.
   */
  private Collection<Field> getAllFields(final Object object) {
    final Collection<Field> fields = new ArrayList<Field>();
    fields.addAll(Arrays.asList(object.getClass().getDeclaredFields()));
    // inspect super classes
    Class<?> superClass = object.getClass().getSuperclass();
    do {
      fields.addAll(Arrays.asList(superClass.getDeclaredFields()));
      superClass = superClass.getSuperclass();
    } while (superClass != null);
    return fields;
  }

  /**
   * Analyze the field containing {@link Inject} annotation and set its value to appropriate value. Override this method
   * if you want to inject something else but uriLocatorFactory.
   *
   * @param object
   *          an object containing @Inject annotation.
   * @param field
   *          {@link Field} object containing {@link Inject} annotation.
   * @return true if field was injected with some not null value.
   * @throws IllegalAccessException
   */
  private boolean acceptAnnotatedField(final Object object, final Field field)
      throws IllegalAccessException {
    boolean accept = false;
    // accept private modifiers
    field.setAccessible(true);
    if (Context.isContextSet()) {
      for (final Map.Entry<Class<?>, Object> entry : map.entrySet()) {
        if (entry.getKey().isAssignableFrom(field.getType())) {
          Object value = entry.getValue();
          // treat factories as a special case for lazy load of the objects.
          if (value instanceof InjectorObjectFactory) {
            value = ((InjectorObjectFactory<?>) value).create();
          }
          field.set(object, value);
          accept = true;
          break;
        }
      }
      // accept injecting unsupported but initialized types
      if (!accept) {
        accept = field.get(object) != null;
        // if (accept |= field.get(object) != null) {
        if (accept) {
          processInjectAnnotation(field.get(object));
        }
      }
    }
    return accept;
  }
}
