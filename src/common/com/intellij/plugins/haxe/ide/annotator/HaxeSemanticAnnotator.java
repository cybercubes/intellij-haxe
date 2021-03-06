/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2015 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 * Copyright 2017-2018 Ilya Malanin
 * Copyright 2018 Eric Bishton
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.ide.annotator;

import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.TextRange;
import com.intellij.plugins.haxe.HaxeBundle;
import com.intellij.plugins.haxe.ide.quickfix.CreateGetterSetterQuickfix;
import com.intellij.plugins.haxe.ide.quickfix.HaxeSwitchMutabilityModifier;
import com.intellij.plugins.haxe.lang.lexer.HaxeTokenTypes;
import com.intellij.plugins.haxe.lang.psi.*;
import com.intellij.plugins.haxe.model.*;
import com.intellij.plugins.haxe.model.fixer.HaxeFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierAddFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierRemoveFixer;
import com.intellij.plugins.haxe.model.fixer.HaxeModifierReplaceVisibilityFixer;
import com.intellij.plugins.haxe.model.type.*;
import com.intellij.plugins.haxe.util.HaxeAbstractEnumUtil;
import com.intellij.plugins.haxe.util.HaxeResolveUtil;
import com.intellij.plugins.haxe.util.PsiFileUtils;
import com.intellij.psi.*;
import com.intellij.util.containers.ContainerUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.intellij.plugins.haxe.lang.psi.HaxePsiModifier.*;

public class HaxeSemanticAnnotator implements Annotator {
  @Override
  public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
    analyzeSingle(element, holder);
  }

  private static void analyzeSingle(final PsiElement element, AnnotationHolder holder) {
    if (element instanceof HaxePackageStatement) {
      PackageChecker.check((HaxePackageStatement)element, holder);
    } else if (element instanceof HaxeMethod) {
      MethodChecker.check((HaxeMethod)element, holder);
    } else if (element instanceof HaxeClass) {
      ClassChecker.check((HaxeClass)element, holder);
    } else if (element instanceof HaxeType) {
      TypeChecker.check((HaxeType)element, holder);
    } else if (element instanceof HaxeFieldDeclaration) {
      FieldChecker.check((HaxeFieldDeclaration)element, holder);
    } else if (element instanceof HaxeLocalVarDeclaration) {
      LocalVarChecker.check((HaxeLocalVarDeclaration)element, holder);
    } else if (element instanceof HaxeStringLiteralExpression) {
      StringChecker.check((HaxeStringLiteralExpression)element, holder);
    }
  }
}

class TypeTagChecker {
  public static void check(
    final PsiElement erroredElement,
    final HaxeTypeTag tag,
    final HaxeVarInit initExpression,
    boolean requireConstant,
    final AnnotationHolder holder
  ) {
    final ResultHolder type1 = HaxeTypeResolver.getTypeFromTypeTag(tag, erroredElement);
    final ResultHolder type2 = getTypeFromVarInit(initExpression);

    final HaxeDocumentModel document = HaxeDocumentModel.fromElement(tag);
    if (!type1.canAssign(type2)) {
      // @TODO: Move to bundle
      Annotation annotation =
        holder.createErrorAnnotation(erroredElement, "Incompatible type " +
                                                     type1.toStringWithoutConstant() +
                                                     " can't be assigned from " +
                                                     type2.toStringWithoutConstant());
      annotation.registerFix(new HaxeFixer("Change type") {
        @Override
        public void run() {
          document.replaceElementText(tag, ":" + type2.toStringWithoutConstant());
        }
      });
      annotation.registerFix(new HaxeFixer("Remove init") {
        @Override
        public void run() {
          document.replaceElementText(initExpression, "", StripSpaces.BEFORE);
        }
      });
    } else if (requireConstant && type2.getType().getConstant() == null) {
      // TODO: Move to bundle
      holder.createErrorAnnotation(erroredElement, "Parameter default type should be constant but was " + type2);
    }
  }

  @NotNull
  private static ResultHolder getTypeFromVarInit(HaxeVarInit init) {
    final ResultHolder abstractEnumFieldInitType = HaxeAbstractEnumUtil.getStaticMemberExpression(init.getExpression());
    if (abstractEnumFieldInitType != null) {
      return abstractEnumFieldInitType;
    }
    // fallback to simple init expression
    return HaxeTypeResolver.getPsiElementType(init.getExpression(), HaxeGenericResolverUtil.generateResolverFromScopeParents(init));
  }
}

class LocalVarChecker {
  public static void check(final HaxeLocalVarDeclaration var, final AnnotationHolder holder) {
    HaxeLocalVarModel local = new HaxeLocalVarModel(var);
    if (local.hasInitializer() && local.hasTypeTag()) {
      TypeTagChecker.check(local.getBasePsi(), local.getTypeTagPsi(), local.getInitializerPsi(), false, holder);
    }
  }
}

class FieldChecker {
  public static void check(final HaxeFieldDeclaration var, final AnnotationHolder holder) {
    HaxeFieldModel field = new HaxeFieldModel(var);
    if (field.isProperty()) {
      checkProperty(field, holder);
    } else {
      if (field.isFinal() && !field.hasInitializer()) {
        if (field.isStatic()) {
          holder.createErrorAnnotation(var, HaxeBundle.message("haxe.semantic.final.static.var.init", field.getName()));
        } else if (!isFieldInitializedInTheConstructor(field)) {
          holder.createErrorAnnotation(var, HaxeBundle.message("haxe.semantic.final.var.init", field.getName()));
        }
      }
    }

    if (field.hasInitializer() && field.hasTypeTag()) {
      TypeTagChecker.check(field.getBasePsi(), field.getTypeTagPsi(), field.getInitializerPsi(), false, holder);
    }

    // Checking for variable redefinition.
    HashSet<HaxeClassModel> classSet = new HashSet<>();
    HaxeClassModel fieldDeclaringClass = field.getDeclaringClass();
    classSet.add(fieldDeclaringClass);
    while (fieldDeclaringClass != null) {
      fieldDeclaringClass = fieldDeclaringClass.getParentClass();
      if (classSet.contains(fieldDeclaringClass)) {
        break;
      } else {
        classSet.add(fieldDeclaringClass);
      }
      if (fieldDeclaringClass != null) {
        for (HaxeFieldModel parentField : fieldDeclaringClass.getFields()) {
          if (parentField.getName().equals(field.getName())) {
            String message;
            if (parentField.isStatic()) {
              message = HaxeBundle.message("haxe.semantic.static.field.override", field.getName());
              holder.createWeakWarningAnnotation(field.getNameOrBasePsi(), message);
            } else {
              message = HaxeBundle.message("haxe.semantic.variable.redefinition", field.getName(), fieldDeclaringClass.getName());
              holder.createErrorAnnotation(field.getBasePsi(), message);
            }
            break;
          }
        }
      }
    }
  }

  private static boolean isFieldInitializedInTheConstructor(HaxeFieldModel field) {
    HaxeClassModel declaringClass = field.getDeclaringClass();
    if (declaringClass == null) return false;
    HaxeMethodModel constructor = declaringClass.getConstructor();
    if (constructor == null) return false;
    PsiElement body = constructor.getBodyPsi();
    if (body == null) return false;

    final InitVariableVisitor visitor = new InitVariableVisitor(field.getName());
    body.accept(visitor);
    return visitor.result;
  }

  private static void checkProperty(final HaxeFieldModel field, final AnnotationHolder holder) {
    final HaxeDocumentModel document = field.getDocument();

    if (field.getGetterPsi() != null && !field.getGetterType().isValidGetter()) {
      holder.createErrorAnnotation(field.getGetterPsi(), "Invalid getter accessor");
    }

    if (field.getSetterPsi() != null && !field.getSetterType().isValidSetter()) {
      holder.createErrorAnnotation(field.getSetterPsi(), "Invalid setter accessor");
    }

    if (field.isFinal()) {
      holder
        .createErrorAnnotation(field.getBasePsi(), HaxeBundle.message("haxe.semantic.property.cant.be.final"))
        .registerFix(new HaxeSwitchMutabilityModifier((HaxeFieldDeclaration)field.getBasePsi()));
    } else if (field.isProperty() && !field.isRealVar() && field.hasInitializer()) {
      final HaxeVarInit psi = field.getInitializerPsi();
      Annotation annotation = holder.createErrorAnnotation(
        field.getInitializerPsi(),
        "This field cannot be initialized because it is not a real variable"
      );
      annotation.registerFix(new HaxeFixer("Remove init") {
        @Override
        public void run() {
          document.replaceElementText(psi, "", StripSpaces.BEFORE);
        }
      });
      annotation.registerFix(new HaxeFixer("Add @:isVar") {
        @Override
        public void run() {
          field.getModifiers().addModifier(IS_VAR);
        }
      });
      if (field.getSetterPsi() != null) {
        annotation.registerFix(new HaxeFixer("Make setter null") {
          @Override
          public void run() {
            document.replaceElementText(field.getSetterPsi(), "null");
          }
        });
      }
    }
    checkPropertyAccessorMethods(field, holder);
  }

  private static void checkPropertyAccessorMethods(final HaxeFieldModel field, final AnnotationHolder holder) {
    if (field.getDeclaringClass().isInterface()) {
      return;
    }

    if (field.getGetterType() == HaxeAccessorType.GET) {
      final String methodName = "get_" + field.getName();

      HaxeMethodModel method = field.getDeclaringClass().getMethod(methodName);
      if (method == null && field.getGetterPsi() != null) {
        holder
          .createErrorAnnotation(field.getGetterPsi(), "Can't find method " + methodName)
          .registerFix(new CreateGetterSetterQuickfix(field.getDeclaringClass(), field, true));
      }
    }

    if (field.getSetterType() == HaxeAccessorType.SET) {
      final String methodName = "set_" + field.getName();

      HaxeMethodModel method = field.getDeclaringClass().getMethod(methodName);
      if (method == null && field.getSetterPsi() != null) {
        holder
          .createErrorAnnotation(field.getSetterPsi(), "Can't find method " + methodName)
          .registerFix(new CreateGetterSetterQuickfix(field.getDeclaringClass(), field, false));
      }
    }
  }
}

class TypeChecker {
  static public void check(final HaxeType type, final AnnotationHolder holder) {
    check(type.getReferenceExpression().getIdentifier(), holder);
  }

  static public void check(final PsiIdentifier identifier, final AnnotationHolder holder) {
    if (identifier == null) return;
    final String typeName = identifier.getText();
    if (!HaxeClassModel.isValidClassName(typeName)) {
      Annotation annotation = holder.createErrorAnnotation(identifier, "Type name must start by upper case");
      annotation.registerFix(new HaxeFixer("Change name") {
        @Override
        public void run() {
          HaxeDocumentModel.fromElement(identifier).replaceElementText(
            identifier,
            typeName.substring(0, 1).toUpperCase() + typeName.substring(1)
          );
        }
      });
    }
  }
}

class ClassChecker {
  static public void check(final HaxeClass clazzPsi, final AnnotationHolder holder) {
    HaxeClassModel clazz = clazzPsi.getModel();
    checkDuplicatedFields(clazz, holder);
    checkClassName(clazz, holder);
    checkInterfaces(clazz, holder);
    checkExtends(clazz, holder);
    checkInterfacesMethods(clazz, holder);
  }

  static private void checkDuplicatedFields(final HaxeClassModel clazz, final AnnotationHolder holder) {
    Map<String, HaxeMemberModel> map = new HashMap<>();
    Set<HaxeMemberModel> repeatedMembers = new HashSet<>();
    for (HaxeMemberModel member : clazz.getMembersSelf()) {
      final String memberName = member.getName();
      HaxeMemberModel repeatedMember = map.get(memberName);
      if (repeatedMember != null) {
        repeatedMembers.add(member);
        repeatedMembers.add(repeatedMember);
      } else {
        map.put(memberName, member);
      }
    }

    for (HaxeMemberModel member : repeatedMembers) {
      holder.createErrorAnnotation(member.getNameOrBasePsi(), "Duplicate class field declaration : " + member.getName());
    }


    //Duplicate class field declaration
  }

  static private void checkClassName(final HaxeClassModel clazz, final AnnotationHolder holder) {
    TypeChecker.check(clazz.getNamePsi(), holder);
  }

  private static void checkExtends(final HaxeClassModel clazz, final AnnotationHolder holder) {
    HaxeClassModel reference = clazz.getParentClass();
    if (reference != null) {
      if (isAnonymousType(clazz)) {
        if (!isAnonymousType(reference)) {
          // @TODO: Move to bundle
          holder.createErrorAnnotation(clazz.haxeClass.getHaxeExtendsList().get(0), "Not an anonymous type");
        }
      } else if (clazz.isInterface()) {
        if (!reference.isInterface()) {
          // @TODO: Move to bundle
          holder.createErrorAnnotation(reference.getPsi(), "Not an interface");
        }
      } else if (!reference.isClass()) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(reference.getPsi(), "Not a class");
      }

      final String qname1 = reference.haxeClass.getQualifiedName();
      final String qname2 = clazz.haxeClass.getQualifiedName();
      if (qname1.equals(qname2)) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(clazz.haxeClass.getHaxeExtendsList().get(0), "Cannot extend self");
      }
    }
  }

  static private boolean isAnonymousType(HaxeClassModel clazz) {
    if (clazz != null && clazz.haxeClass != null) {
      HaxeClass haxeClass = clazz.haxeClass;
      if (haxeClass instanceof HaxeAnonymousType) {
        return true;
      }
      if (haxeClass instanceof HaxeTypedefDeclaration) {
        HaxeTypeOrAnonymous anonOrType = ((HaxeTypedefDeclaration)haxeClass).getTypeOrAnonymous();
        if (anonOrType != null) {
          return anonOrType.getAnonymousType() != null;
        }
      }
    }
    return false;
  }

  private static void checkInterfaces(final HaxeClassModel clazz, final AnnotationHolder holder) {
    for (HaxeClassReferenceModel interfaze : clazz.getImplementingInterfaces()) {
      if (interfaze.getHaxeClass() == null || !interfaze.getHaxeClass().isInterface()) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(interfaze.getPsi(), "Not an interface");
      }
    }
  }

  private static void checkInterfacesMethods(final HaxeClassModel clazz, final AnnotationHolder holder) {
    for (HaxeClassReferenceModel reference : clazz.getImplementingInterfaces()) {
      checkInterfaceMethods(clazz, reference, holder);
    }
  }

  private static void checkInterfaceMethods(
    final HaxeClassModel clazz,
    final HaxeClassReferenceModel intReference,
    final AnnotationHolder holder
  ) {
    final List<HaxeMethodModel> missingMethods = new ArrayList<HaxeMethodModel>();
    final List<String> missingMethodsNames = new ArrayList<String>();

    if (intReference.getHaxeClass() != null) {
      for (HaxeMethodModel intMethod : intReference.getHaxeClass().getMethods()) {
        if (!intMethod.isStatic()) {
          // Implemented method not necessarily located in current class
          final PsiMethod[] methods = clazz.haxeClass.findMethodsByName(intMethod.getName(), true);
          final PsiMethod psiMethod = ContainerUtil.find(methods, new Condition<PsiMethod>() {
            @Override
            public boolean value(PsiMethod method) {
              return method instanceof HaxeMethod;
            }
          });

          if (psiMethod == null) {
            missingMethods.add(intMethod);
            missingMethodsNames.add(intMethod.getName());
          } else {
            final HaxeMethod method = (HaxeMethod)psiMethod;
            final HaxeMethodModel methodModel = method.getModel();

            // We should check if signature in inherited method differs from method provided by interface
            if (methodModel.getDeclaringClass() != clazz) {
              if (MethodChecker.checkIfMethodSignatureDiffers(methodModel, intMethod)) {
                final HaxeClass parentClass = methodModel.getDeclaringClass().haxeClass;

                final String errorMessage = HaxeBundle.message(
                  "haxe.semantic.implemented.super.method.signature.differs",
                  method.getName(),
                  parentClass.getQualifiedName(),
                  intMethod.getPresentableText(HaxeMethodContext.NO_EXTENSION),
                  methodModel.getPresentableText(HaxeMethodContext.NO_EXTENSION)
                );

                holder.createErrorAnnotation(intReference.getPsi(), errorMessage);
              }
            } else {
              MethodChecker.checkMethodsSignatureCompatibility(methodModel, intMethod, holder);
            }
          }
        }
      }
    }

    if (missingMethods.size() > 0) {
      // @TODO: Move to bundle
      Annotation annotation = holder.createErrorAnnotation(
        intReference.getPsi(),
        "Not implemented methods: " + StringUtils.join(missingMethodsNames, ", ")
      );
      annotation.registerFix(new HaxeFixer("Implement methods") {
        @Override
        public void run() {
          clazz.addMethodsFromPrototype(missingMethods);
        }
      });
    }
  }
}

class MethodChecker {
  static public void check(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    checkTypeTagInInterfacesAndExternClass(currentMethod, holder);
    checkMethodArguments(currentMethod, holder);
    checkOverride(methodPsi, holder);
    if (HaxeSemanticAnnotatorConfig.ENABLE_EXPERIMENTAL_BODY_CHECK) {
      MethodBodyChecker.check(methodPsi, holder);
    }
    //currentMethod.getBodyPsi()
  }

  private static void checkTypeTagInInterfacesAndExternClass(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    if (currentClass.isExtern() || currentClass.isInterface()) {
      if (currentMethod.getReturnTypeTagPsi() == null && !currentMethod.isConstructor()) {
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), HaxeBundle.message("haxe.semantic.type.required"));
      }
      for (final HaxeParameterModel param : currentMethod.getParameters()) {
        if (param.getTypeTagPsi() == null) {
          holder.createErrorAnnotation(param.getBasePsi(), HaxeBundle.message("haxe.semantic.type.required"));
        }
      }
    }
  }

  private static void checkMethodArguments(final HaxeMethodModel currentMethod, final AnnotationHolder holder) {
    boolean hasOptional = false;
    HashMap<String, PsiElement> argumentNames = new HashMap<String, PsiElement>();
    for (final HaxeParameterModel param : currentMethod.getParameters()) {
      String paramName = param.getName();

      if (param.hasOptionalPsi() && param.getVarInitPsi() != null) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getOptionalPsi(), "Optional not needed when specified an init value");
      }
      if (param.getVarInitPsi() != null && param.getTypeTagPsi() != null) {
        TypeTagChecker.check(
          param.getBasePsi(),
          param.getTypeTagPsi(),
          param.getVarInitPsi(),
          true,
          holder
        );
      }
      if (param.isOptional()) {
        hasOptional = true;
      } else if (hasOptional) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getBasePsi(), "Non-optional argument after optional argument");
      }

      if (argumentNames.containsKey(paramName)) {
        // @TODO: Move to bundle
        holder.createWarningAnnotation(param.getNamePsi(), "Repeated argument name '" + paramName + "'");
        holder.createWarningAnnotation(argumentNames.get(paramName), "Repeated argument name '" + paramName + "'");
      } else {
        argumentNames.put(paramName, param.getNamePsi());
      }
    }
  }

  private static final String[] OVERRIDE_FORBIDDEN_MODIFIERS = {FINAL, FINAL_META, INLINE, STATIC};
  private static void checkOverride(final HaxeMethod methodPsi, final AnnotationHolder holder) {
    final HaxeMethodModel currentMethod = methodPsi.getModel();
    final HaxeClassModel currentClass = currentMethod.getDeclaringClass();
    final HaxeModifiersModel currentModifiers = currentMethod.getModifiers();

    final HaxeClassModel parentClass = (currentClass != null) ? currentClass.getParentClass() : null;
    final HaxeMethodModel parentMethod = parentClass != null ? parentClass.getMethod(currentMethod.getName()) : null;
    final HaxeModifiersModel parentModifiers = (parentMethod != null) ? parentMethod.getModifiers() : null;

    boolean requiredOverride = false;

    if (currentMethod.isConstructor()) {
      if (currentModifiers.hasModifier(STATIC)) {
        // @TODO: Move to bundle
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Constructor can't be static").registerFix(
          new HaxeModifierRemoveFixer(currentModifiers, STATIC)
        );
      }
    } else if (currentMethod.isStaticInit()) {
      if (!currentModifiers.hasModifier(STATIC)) {
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "__init__ must be static").registerFix(
          new HaxeModifierAddFixer(currentModifiers, STATIC)
        );
      }
    } else if (parentMethod != null) {
      if (parentMethod.isStatic()) {
        holder.createWarningAnnotation(currentMethod.getNameOrBasePsi(), "Method '" + currentMethod.getName()
                                                                         + "' overrides a static method of a superclass");
      } else {
        requiredOverride = true;

        if (parentModifiers.hasAnyModifier(OVERRIDE_FORBIDDEN_MODIFIERS)) {
          Annotation annotation =
            holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Can't override static, inline or final methods");
          for (String modifier : OVERRIDE_FORBIDDEN_MODIFIERS) {
            if (parentModifiers.hasModifier(modifier)) {
              annotation.registerFix(
                new HaxeModifierRemoveFixer(parentModifiers, modifier, "Remove " + modifier + " from " + parentMethod.getFullName())
              );
            }
          }
        }

        if (HaxePsiModifier.hasLowerVisibilityThan(currentModifiers.getVisibility(), parentModifiers.getVisibility())) {
          Annotation annotation = holder.createErrorAnnotation(
            currentMethod.getNameOrBasePsi(),
            "Field " +
            currentMethod.getName() +
            " has less visibility (public/private) than superclass one"
          );
          annotation.registerFix(
            new HaxeModifierReplaceVisibilityFixer(currentModifiers, parentModifiers.getVisibility(), "Change current method visibility"));
          annotation.registerFix(
            new HaxeModifierReplaceVisibilityFixer(parentModifiers, currentModifiers.getVisibility(), "Change parent method visibility"));
        }
      }
    }

    //System.out.println(aClass);
    if (currentModifiers.hasModifier(OVERRIDE) && !requiredOverride) {
      holder.createErrorAnnotation(currentModifiers.getModifierPsi(OVERRIDE), "Overriding nothing").registerFix(
        new HaxeModifierRemoveFixer(currentModifiers, OVERRIDE)
      );
    } else if (requiredOverride) {
      if (!currentModifiers.hasModifier(OVERRIDE)) {
        holder.createErrorAnnotation(currentMethod.getNameOrBasePsi(), "Must override").registerFix(
          new HaxeModifierAddFixer(currentModifiers, OVERRIDE)
        );
      } else {
        // It is rightly overriden. Now check the signature.
        checkMethodsSignatureCompatibility(currentMethod, parentMethod, holder);
      }
    }
  }

  static void checkMethodsSignatureCompatibility(
    @NotNull final HaxeMethodModel currentMethod,
    @NotNull final HaxeMethodModel parentMethod,
    final AnnotationHolder holder
  ) {
    final HaxeDocumentModel document = currentMethod.getDocument();

    List<HaxeParameterModel> currentParameters = currentMethod.getParameters();
    final List<HaxeParameterModel> parentParameters = parentMethod.getParameters();
    int minParameters = Math.min(currentParameters.size(), parentParameters.size());

    if (currentParameters.size() > parentParameters.size()) {
      for (int n = minParameters; n < currentParameters.size(); n++) {
        final HaxeParameterModel currentParam = currentParameters.get(n);
        holder.createErrorAnnotation(currentParam.getBasePsi(), "Unexpected argument").registerFix(
          new HaxeFixer("Remove argument") {
            @Override
            public void run() {
              currentParam.remove();
            }
          });
      }
    } else if (currentParameters.size() != parentParameters.size()) {
      holder.createErrorAnnotation(
        currentMethod.getNameOrBasePsi(),
        "Not matching arity expected " +
        parentParameters.size() +
        " arguments but found " +
        currentParameters.size()
      );
    }

    for (int n = 0; n < minParameters; n++) {
      final HaxeParameterModel currentParam = currentParameters.get(n);
      final HaxeParameterModel parentParam = parentParameters.get(n);
      if (!HaxeTypeCompatible.canAssignToFrom(currentParam.getType(), parentParam.getType())) {
        holder.createErrorAnnotation(
          currentParam.getBasePsi(),
          "Type " + currentParam.getType() + " is not compatible with " + parentParam.getType()).registerFix
          (
            new HaxeFixer("Change type") {
              @Override
              public void run() {
                document.replaceElementText(currentParam.getTypeTagPsi(), parentParam.getTypeTagPsi().getText());
              }
            }
          );
      }

      if (currentParam.hasOptionalPsi() != parentParam.hasOptionalPsi()) {
        final boolean removeOptional = currentParam.hasOptionalPsi();

        String errorMessage;
        if (parentMethod.getDeclaringClass().isInterface()) {
          errorMessage = removeOptional ? "haxe.semantic.implemented.method.parameter.required"
                                        : "haxe.semantic.implemented.method.parameter.optional";
        } else {
          errorMessage = removeOptional ? "haxe.semantic.overwritten.method.parameter.required"
                                        : "haxe.semantic.overwritten.method.parameter.optional";
        }

        errorMessage = HaxeBundle.message(errorMessage, parentParam.getPresentableText(),
                                          parentMethod.getDeclaringClass().getName() + "." + parentMethod.getName());

        final Annotation annotation = holder.createErrorAnnotation(currentParam.getBasePsi(), errorMessage);
        final String localFixName = HaxeBundle.message(removeOptional ? "haxe.semantic.method.parameter.optional.remove"
                                                                      : "haxe.semantic.method.parameter.optional.add");

        annotation.registerFix(
          new HaxeFixer(localFixName) {
            @Override
            public void run() {
              if (removeOptional) {
                currentParam.getOptionalPsi().delete();
              } else {
                PsiElement element = currentParam.getBasePsi();
                document.addTextBeforeElement(element.getFirstChild(), "?");
              }
            }
          }
        );
      }
    }

    ResultHolder currentResult = currentMethod.getResultType();
    ResultHolder parentResult = parentMethod.getResultType();
    if (!currentResult.canAssign(parentResult)) {
      PsiElement psi = currentMethod.getReturnTypeTagOrNameOrBasePsi();
      holder.createErrorAnnotation(psi, "Not compatible return type " + currentResult + " != " + parentResult);
    }
  }

  // Fast check without annotations
  static boolean checkIfMethodSignatureDiffers(HaxeMethodModel source, HaxeMethodModel prototype) {
    final List<HaxeParameterModel> sourceParameters = source.getParameters();
    final List<HaxeParameterModel> prototypeParameters = prototype.getParameters();

    if (sourceParameters.size() != prototypeParameters.size()) {
      return true;
    }

    final int parametersCount = sourceParameters.size();

    for (int n = 0; n < parametersCount; n++) {
      final HaxeParameterModel sourceParam = sourceParameters.get(n);
      final HaxeParameterModel prototypeParam = prototypeParameters.get(n);
      if (!HaxeTypeCompatible.canAssignToFrom(sourceParam.getType(), prototypeParam.getType()) ||
          sourceParam.isOptional() != prototypeParam.isOptional()) {
        return true;
      }
    }

    ResultHolder currentResult = source.getResultType();
    ResultHolder prototypeResult = prototype.getResultType();

    return !currentResult.canAssign(prototypeResult);
  }
}

class PackageChecker {
  static public void check(final HaxePackageStatement element, final AnnotationHolder holder) {
    final HaxeReferenceExpression expression = element.getReferenceExpression();
    String packageName = (expression != null) ? expression.getText() : "";
    PsiDirectory fileDirectory = element.getContainingFile().getParent();
    if (fileDirectory == null) return;
    List<PsiFileSystemItem> fileRange = PsiFileUtils.getRange(PsiFileUtils.findRoot(fileDirectory), fileDirectory);
    fileRange.remove(0);
    String actualPath = PsiFileUtils.getListPath(fileRange);
    final String actualPackage = actualPath.replace('/', '.');
    final String actualPackage2 = HaxeResolveUtil.getPackageName(element.getContainingFile());
    // @TODO: Should use HaxeResolveUtil

    for (String s : StringUtils.split(packageName, '.')) {
      if (!s.substring(0, 1).toLowerCase().equals(s.substring(0, 1))) {
        //HaxeSemanticError.addError(element, new HaxeSemanticError("Package name '" + s + "' must start with a lower case character"));
        // @TODO: Move to bundle
        holder.createErrorAnnotation(element, "Package name '" + s + "' must start with a lower case character");
      }
    }

    if (!packageName.equals(actualPackage)) {
      holder.createErrorAnnotation(
        element,
        "Invalid package name! '" + packageName + "' should be '" + actualPackage + "'").registerFix(
        new HaxeFixer("Fix package") {
          @Override
          public void run() {
            Document document =
              PsiDocumentManager.getInstance(element.getProject()).getDocument(element.getContainingFile());

            if (expression != null) {
              TextRange range = expression.getTextRange();
              document.replaceString(range.getStartOffset(), range.getEndOffset(), actualPackage);
            } else {
              int offset =
                element.getNode().findChildByType(HaxeTokenTypes.OSEMI).getTextRange().getStartOffset();
              document.replaceString(offset, offset, actualPackage);
            }
          }
        }
      );
    }
  }
}

class MethodBodyChecker {
  public static void check(HaxeMethod psi, AnnotationHolder holder) {
    final HaxeMethodModel method = psi.getModel();
    HaxeTypeResolver.getPsiElementType(method.getBodyPsi(), holder, generateConstraintResolver(method));
  }

  @NotNull
  private static HaxeGenericResolver generateConstraintResolver(HaxeMethodModel method) {
    HaxeGenericResolver resolver = new HaxeGenericResolver();
    for (HaxeGenericParamModel param : method.getGenericParams()) {
      ResultHolder constraint = param.getConstraint(resolver);
      if (null == constraint.getType()) {
        constraint = new ResultHolder(SpecificHaxeClassReference.getDynamic(param.getPsi()));
      }
      resolver.add(param.getName(), constraint);
    }
    return resolver;
  }
}

class StringChecker {
  public static void check(HaxeStringLiteralExpression psi, AnnotationHolder holder) {
    if (isSingleQuotesRequired(psi)) {
      holder.createWarningAnnotation(psi, "Expressions that contains string interpolation should be wrapped with single quotes");
    }
  }

  private static boolean isSingleQuotesRequired(HaxeStringLiteralExpression psi) {
    return (psi.getLongTemplateEntryList().size() > 0 || psi.getShortTemplateEntryList().size() > 0) &&
           psi.getFirstChild().textContains('"');
  }
}

class InitVariableVisitor extends HaxeVisitor {
  public boolean result = false;

  private final String fieldName;

  InitVariableVisitor(String fieldName) {
    this.fieldName = fieldName;
  }

  @Override
  public void visitElement(PsiElement element) {
    super.visitElement(element);
    if (result) return;
    if (element instanceof HaxeIdentifier || element instanceof HaxePsiToken || element instanceof HaxeStringLiteralExpression) return;
    element.acceptChildren(this);
  }

  @Override
  public void visitAssignExpression(@NotNull HaxeAssignExpression o) {
    HaxeExpression expression = (o.getExpressionList()).get(0);
    if (expression instanceof HaxeReferenceExpression) {
      final HaxeReferenceExpression reference = (HaxeReferenceExpression)expression;
      final HaxeIdentifier identifier = reference.getIdentifier();

      if (identifier.getText().equals(fieldName)) {
        PsiElement firstChild = reference.getFirstChild();
        if (firstChild instanceof HaxeThisExpression || firstChild == identifier) {
          this.result = true;
          return;
        }
      }
    }

    super.visitAssignExpression(o);
  }
}