/*
    Unigrid Hedgehog
    Copyright © 2021-2023 Stiftelsen The Unigrid Foundation, UGD Software AB

    Stiftelsen The Unigrid Foundation (org. nr: 802482-2408)
    UGD Software AB (org. nr: 559339-5824)

    This program is free software: you can redistribute it and/or modify it under the terms of the
    addended GNU Affero General Public License as published by the The Unigrid Foundation and
    the Free Software Foundation, version 3 of the License (see COPYING and COPYING.addendum).

    This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
    even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU Affero General Public License and the addendum for more details.

    You should have received an addended copy of the GNU Affero General Public License with this program.
    If not, see <http://www.gnu.org/licenses/> and <https://github.com/unigrid-project/hedgehog>.
 */

    package org.unigrid.hedgehog.model.cdi;

    import jakarta.annotation.PostConstruct;
    import jakarta.enterprise.inject.Any;
    import jakarta.enterprise.inject.Instance;
    import jakarta.enterprise.inject.spi.CDI;
    import java.lang.annotation.Annotation;
    import java.lang.reflect.Field;
    
    /**
     * Bas-klass för resurser som behöver manuellt brygga CDI-injektion
     * via {@link CDIBridgeInject}.
     *
     * Används när objekt inte skapas av CDI själv (ex. Netty, CLI, etc).
     */
    public abstract class CDIBridgeResource {
    
        @PostConstruct
        private void init() {
            for (Field field : this.getClass().getDeclaredFields()) {
    
                if (!field.isAnnotationPresent(CDIBridgeInject.class)) {
                    continue;
                }
    
                injectField(field);
            }
        }
    
        private void injectField(Field field) {
            try {
                field.setAccessible(true);
    
                Annotation[] qualifiers = field.getAnnotations();
                Instance<?> instance = CDI.current().select(field.getType(), qualifiers);
    
                if (instance.isUnsatisfied()) {
                    throw new IllegalStateException(
                        "CDI injection failed: no bean found for field "
                        + field.getName() + " of type " + field.getType().getName()
                    );
                }
    
                if (instance.isAmbiguous()) {
                    throw new IllegalStateException(
                        "CDI injection failed: ambiguous beans for field "
                        + field.getName() + " of type " + field.getType().getName()
                    );
                }
    
                field.set(this, instance.get());
    
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(
                    "Failed to inject CDI field: " + field.getName(), ex
                );
            }
        }
    }
    