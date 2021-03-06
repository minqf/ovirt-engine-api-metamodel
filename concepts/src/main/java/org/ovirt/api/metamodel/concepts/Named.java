/*
Copyright (c) 2016 Red Hat, Inc.

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

import java.util.Objects;
import java.util.function.Predicate;

/**
 * This interface represents an object that has a name.
 */
public interface Named extends Comparable<Named> {
    /**
     * Returns the name of the object.
     */
    Name getName();

    /**
     * Compares this object to another named object. Only the name is taken into account for this comparison, and the
     * result is intended only for sorting concepts by name. In particular if the result is 0 it only means that
     * both objects have the same name, not that they are equal.
     */
    @Override
    default int compareTo(Named that) {
        Name thisName = this.getName();
        Name thatName = that.getName();
        if (thisName == null && thatName != null) {
            return -1;
        }
        if (thisName == null) {
            return 0;
        }
        if (thatName == null) {
            return 1;
        }
        return thisName.compareTo(thatName);
    }

    /**
     * This method creates a predicate useful for filtering streams of concepts and keeping only the ones that have a
     * given name. For example, if you need to find the a parameter of a method that has a given name you can do the
     * following:
     *
     * <pre>
     * Name name = ...;
     * Optional<Parameter> parameter = method.getParameters().stream()
     *     .filter(named(name))
     *     .findFirst();
     * </pre>
     *
     * In rare cases, the name of a concept will start with an underscore to avoid a conflict with a java keyword. In
     * these cases the predicate will ignore the underscore when comparing the names.
     *
     * @param name the name that the predicate will accept
     * @return a predicate that accepts concepts with the given name
     */
    static Predicate<Named> named(Name name) {
        return x -> namesEqual(name, x);
    }

    static boolean namesEqual(Name name1, Named name2) {
        if (name1 != null) {
            String firstWord = name1.getWords().get(0);
            if (firstWord.startsWith("_")) {
                name1.setWord(0, firstWord.substring(1, firstWord.length())); //remove the underscore
            }
        }
        return Objects.equals(name2.getName(), name1);
    }
}
