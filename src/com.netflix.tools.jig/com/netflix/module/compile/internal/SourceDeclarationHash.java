/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.netflix.module.compile.internal;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Modifier;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

/** Hash of source declarations that can affect another source in the module. */
final class SourceDeclarationHash extends TreeScanner<Void, DataOutputStream> {
    private final Set<String> identifiers = new HashSet<>();

    static ContentHash of(CompilationUnitTree unit) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 must be available", e);
        }
        try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            new SourceDeclarationHash().scan(unit, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ContentHash.takeOwnership(digest.digest());
    }

    @Override
    public Void scan(Tree tree, DataOutputStream output) {
        try {
            if (tree == null) {
                output.writeInt(-1);
                return null;
            }
            write(output, tree.getKind()
                              .name());
            return super.scan(tree, output);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Void scan(Iterable<? extends Tree> trees, DataOutputStream output) {
        if (trees == null) {
            return scan((Tree) null, output);
        }
        var copy = new ArrayList<Tree>();
        trees.forEach(copy::add);
        try {
            output.writeInt(copy.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        copy.forEach(tree -> scan(tree, output));
        return null;
    }

    @Override
    public Void visitCompilationUnit(CompilationUnitTree unit, DataOutputStream output) {
        identifiers.clear();
        scan(unit.getPackage(), output);
        scan(unit.getTypeDecls(), output);
        scan(unit.getModule(), output);

        var imports = new ArrayList<ImportTree>();
        for (var imported : unit.getImports()) {
            var name = imported.getQualifiedIdentifier();
            if (name instanceof MemberSelectTree select) {
                var member = select.getIdentifier();
                if (member.contentEquals("*") || identifiers.contains(member.toString())) {
                    imports.add(imported);
                }
            } else {
                imports.add(imported);
            }
        }
        imports.sort((first, second) -> {
            if (first.isStatic() != second.isStatic()) {
                return first.isStatic() ? -1 : 1;
            }
            return first.getQualifiedIdentifier()
                        .toString()
                        .compareTo(second.getQualifiedIdentifier()
                                         .toString());
        });
        scan(imports, output);
        return null;
    }

    @Override
    public Void visitIdentifier(IdentifierTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getName());
        identifiers.add(tree.getName()
                            .toString());
        return super.visitIdentifier(tree, output);
    }

    @Override
    public Void visitMemberSelect(MemberSelectTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getIdentifier());
        return super.visitMemberSelect(tree, output);
    }

    @Override
    public Void visitMemberReference(MemberReferenceTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getName());
        return super.visitMemberReference(tree, output);
    }

    @Override
    public Void visitClass(ClassTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getSimpleName());
        scan(tree.getModifiers(), output);
        scan(tree.getTypeParameters(), output);
        scan(tree.getExtendsClause(), output);
        scan(tree.getImplementsClause(), output);
        scan(tree.getPermitsClause(), output);
        scan(tree.getMembers().stream()
                .filter(member -> visibleMember(tree, member))
                .toList(),
                output);
        return null;
    }

    @Override
    public Void visitMethod(MethodTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getName());
        scan(tree.getModifiers(), output);
        scan(tree.getReturnType(), output);
        scan(tree.getTypeParameters(), output);
        scan(tree.getParameters(), output);
        scan(tree.getReceiverParameter(), output);
        scan(tree.getThrows(), output);
        scan(tree.getDefaultValue(), output);
        return null;
    }

    @Override
    public Void visitVariable(VariableTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getName());
        return super.visitVariable(tree, output);
    }

    @Override
    public Void visitLiteral(LiteralTree tree, DataOutputStream output) {
        writeUnchecked(output, String.valueOf(tree.getValue()));
        return super.visitLiteral(tree, output);
    }

    @Override
    public Void visitModifiers(ModifiersTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getFlags()
                .toString());
        return super.visitModifiers(tree, output);
    }

    @Override
    public Void visitPrimitiveType(PrimitiveTypeTree tree, DataOutputStream output) {
        writeUnchecked(output, tree.getPrimitiveTypeKind()
                .name());
        return super.visitPrimitiveType(tree, output);
    }

    private static boolean visibleMember(ClassTree enclosing, Tree member) {
        return switch (member.getKind()) {
            case ANNOTATION_TYPE, CLASS, ENUM, INTERFACE, RECORD ->
                    !isPrivate(((ClassTree) member).getModifiers());
            case METHOD -> !isPrivate(((MethodTree) member).getModifiers());
            case VARIABLE -> enclosing.getKind() == Kind.RECORD || !isPrivate(((VariableTree) member).getModifiers());
            default -> false;
        };
    }

    private static boolean isPrivate(ModifiersTree modifiers) {
        return modifiers.getFlags().contains(Modifier.PRIVATE);
    }

    private static void writeUnchecked(DataOutputStream output, CharSequence value) {
        try {
            write(output, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(DataOutputStream output, CharSequence value) throws IOException {
        var text = value.toString();
        output.writeInt(text.length());
        for (var index = 0; index < text.length(); index++) {
            output.writeChar(text.charAt(index));
        }
    }
}
