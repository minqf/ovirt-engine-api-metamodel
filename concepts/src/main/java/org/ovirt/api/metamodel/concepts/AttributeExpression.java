/*
Copyright (c) 2015 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.ovirt.api.metamodel.concepts;

/**
 * This expression represents the access to structured type attribute.
 */
public class AttributeExpression extends Expression {
    private Expression target;
    private Attribute attribute;

    public Expression getTarget() {
        return target;
    }

    public void setTarget(Expression newTarget) {
        target = newTarget;
    }

    public void setAttribute(Attribute newAttribute) {
        attribute = newAttribute;
    }

    public Attribute getAttribute() {
        return attribute;
    }

    @Override
    public String toString(boolean protect) {
        StringBuilder buffer = new StringBuilder();
        if (target != null) {
            if (protect) {
                buffer.append("(");
            }
            buffer.append(target.toString(true));
            buffer.append(".");
            buffer.append(attribute);
            if (protect) {
                buffer.append(")");
            }
        }
        else {
            buffer.append(attribute);
        }
        return buffer.toString();
    }
}
