// Copyright (c) Runtime Verification, Inc. All Rights Reserved.
package org.kframework.backend;

import java.util.Set;
import java.util.function.Function;
import org.kframework.attributes.Att;
import org.kframework.definition.Definition;
import org.kframework.definition.Module;
import org.kframework.kompile.CompiledDefinition;

/** Created by dwightguth on 9/1/15. */
public interface Backend {

  class Holder {
    public CompiledDefinition def;

    public Holder(CompiledDefinition def) {
      this.def = def;
    }
  }

  void accept(Holder def);

  Function<Definition, Definition> steps();

  Function<Module, Module> specificationSteps(Definition def);

  Set<Att.Key> excludedModuleTags();
}
