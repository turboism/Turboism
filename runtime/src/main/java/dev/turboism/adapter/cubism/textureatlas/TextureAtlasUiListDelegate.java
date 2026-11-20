package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Provides linear-time bridges for {@code TAE_ModelImageList.updateList()} and
 * {@code doc.model.texture.r.a(CModelSource)}.
 */
public final class TextureAtlasUiListDelegate {

    private static final String LOG_COMPONENT = "texture-atlas-ui";
    private static final String UI_LIST_FALLBACK = "Fast image-list update failed, falling back cleanly to native";
    private static final String ENTRY_MAP_FALLBACK = "Fast atlas entry map build failed, falling back to native";

    private static final String UI_LIST_CLASS =
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.modelImageList.TAE_ModelImageList";
    private static final String EDIT_LAYER_CLASS =
            "com.live2d.cubism.doc.modeling.ui.atlasEditor.impl.TAE__EditLayer_ModelImage";
    private static final String MODEL_SOURCE_CLASS = "com.live2d.cubism.doc.model.CModelSource";
    private static final String CONTAINER_LAYER_INTERNAL = "jp/noids/design/layer/core/ContainerLayer";
    private static final String DATA_MODEL_INTERNAL =
            "com/live2d/cubism/doc/modeling/ui/atlasEditor/impl/TAE_DataModel";
    private static final String LAYER_SEL_HOLDER_INTERNAL = "jp/noids/design/layer/c/b";
    private static final String LAYER_REF_CLASS = "jp.noids.design.layer.core.LayerRef";
    private static final String LIST_MODEL_CLASS = "com.live2d.ui.control.CList$b";
    private static final String FIELD_DATA_MODEL = "taeDataModel";
    private static final String FIELD_COMBO = "comboSelector";
    private static final String FIELD_ALL_IMAGES = "allUsingModelImages";
    private static final String FIELD_LIST_WIDGET = "listModelImage";
    private static final String FIELD_FILTER_PARTS = "filterByParts";
    private static final String FIELD_SWITCH_MAP = "a";
    private static final String METHOD_IS_USED = "isUsed";
    private static final String METHOD_IS_VISIBLE = "isVisible";
    private static final String METHOD_IS_SELECTED = "isSelected";
    private static final String METHOD_UPDATE_LIST = "updateList";
    private static final String METHOD_GET_SELECTED_ITEM = "getSelectedItem";
    private static final String METHOD_SET_MODEL = "setModel";
    private static final String METHOD_REPAINT = "repaint";
    private static final String METHOD_ITEM_IMAGE = "a";
    private static final String METHOD_MODEL_IMAGE_GUID = "getGuid";
    private static final String METHOD_LAYER_MODEL_IMAGE = "getModelImage";
    private static final String METHOD_CHILDREN = "getChildren";
    private static final String METHOD_REF_LAYER = "getLayer";
    private static final String METHOD_SELECTED_LAYERS = "a";
    private static final String METHOD_GET_TEXTURE_MANAGER = "getTextureManager";
    private static final String METHOD_GET_ATLASES = "getTextureAtlases";
    private static final String METHOD_GET_ATLAS_IMAGES = "getModelImages";
    private static final String METHOD_ENTRY_GUID = "getModelImageGuid";
    private static final String METHOD_ALL_ART_MESHES = "getAllArtMeshes";
    private static final String METHOD_TEX_INPUT_EXT = "getTextureInputExtension";
    private static final String METHOD_MODEL_IMAGE_INPUT = "getModelImageInput";
    private static final String METHOD_PARENT_GUID = "getParentGuid";
    private static final String CLASS_SUFFIX = ".class";

    private static final int CASE_ALL = 1;
    private static final int CASE_USED = 2;
    private static final int CASE_UNUSED = 3;
    private static final int CASE_UNUSED_VISIBLE = 4;
    private static final int CASE_USED_SELECTED = 5;

    private static final ClassValue<Optional<UiListHost>> LIST_HOSTS = new ClassValue<>() {
        @Override
        protected Optional<UiListHost> computeValue(final Class<?> listClass) {
            return UiListHost.bind(listClass);
        }
    };

    private static final ClassValue<Optional<ConfirmMapHost>> CONFIRM_HOSTS = new ClassValue<>() {
        @Override
        protected Optional<ConfirmMapHost> computeValue(final Class<?> modelSourceClass) {
            return ConfirmMapHost.bind(modelSourceClass);
        }
    };

    private TextureAtlasUiListDelegate() {}

    static boolean canBind(final Class<?> listClass) {
        return listClass != null && LIST_HOSTS.get(listClass).isPresent();
    }

    /**
     * Updates {@code TAE_ModelImageList}'s filtered items in one linear pass per invocation.
     *
     * @param listWidget host {@code TAE_ModelImageList} instance
     * @return {@code true} when updated, or {@code false} to invoke native logic
     */
    public static boolean tryUpdateList(final Object listWidget) {
        if (listWidget == null) return false;
        final Optional<UiListHost> hostOpt = LIST_HOSTS.get(listWidget.getClass());
        if (hostOpt.isEmpty()) return false;
        final UiListHost host = hostOpt.get();
        try {
            return host.updateList(listWidget);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, UI_LIST_FALLBACK, failure);
            return false;
        }
    }

    /**
     * Populates {@code CModelImageGuid -> ModelImageEntry} in one pass across all atlas pages.
     *
     * @param modelSource host {@code CModelSource} instance
     * @param destination host map to populate
     * @return {@code true} on success, or {@code false} to invoke the native nested loops
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean tryPopulateAtlasEntries(final Object modelSource, final Map destination) {
        if (modelSource == null || destination == null) return false;
        final Optional<ConfirmMapHost> hostOpt = CONFIRM_HOSTS.get(modelSource.getClass());
        if (hostOpt.isEmpty()) return false;
        final ConfirmMapHost host = hostOpt.get();
        try {
            final Object textureManager = host.getTextureManager().invoke(modelSource);
            final Iterable<?> atlases = (Iterable<?>) host.getTextureAtlases().invoke(textureManager);
            for (final Object atlas : atlases) {
                final Iterable<?> entries =
                        (Iterable<?>) host.getAtlasModelImages().invoke(atlas);
                for (final Object entry : entries) {
                    final Object guid = host.getEntryGuid().invoke(entry);
                    if (guid == null) {
                        return false;
                    }
                    destination.putIfAbsent(guid, entry);
                }
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, ENTRY_MAP_FALLBACK, failure);
            return false;
        }
    }

    private record ConfirmMapHost(
            Method getTextureManager, Method getTextureAtlases, Method getAtlasModelImages, Method getEntryGuid) {
        static Optional<ConfirmMapHost> bind(final Class<?> modelSourceClass) {
            try {
                if (!MODEL_SOURCE_CLASS.equals(modelSourceClass.getName())) {
                    return Optional.empty();
                }
                final Method getTexMgr = modelSourceClass.getMethod(METHOD_GET_TEXTURE_MANAGER);
                final Class<?> texMgrClass = getTexMgr.getReturnType();
                final Method getAtlases = texMgrClass.getMethod(METHOD_GET_ATLASES);
                final ClassLoader loader = modelSourceClass.getClassLoader();
                final Class<?> atlasClass =
                        loader.loadClass("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas");
                final Class<?> entryClass = loader.loadClass(
                        "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas$ModelImageEntry");
                return Optional.of(new ConfirmMapHost(
                        getTexMgr,
                        getAtlases,
                        atlasClass.getMethod(METHOD_GET_ATLAS_IMAGES),
                        entryClass.getMethod(METHOD_ENTRY_GUID)));
            } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
                return Optional.empty();
            }
        }
    }

    private record UiListHost(
            Field taeDataModelField,
            Field comboSelectorField,
            Field allUsingImagesField,
            Field listModelImageField,
            Field filterByPartsField,
            int[] switchMap,
            Method getSelectedItem,
            Method setModel,
            Method repaint,
            Constructor<?> listModelCtor,
            Method itemModelImage,
            Method modelImageGuid,
            Method isVisibleMethod,
            Class<?> editLayerClass,
            Method layerModelImage,
            Method pagesMethod,
            Method containerLayerMethod,
            Method containerChildren,
            Method layerRefGetLayer,
            Method activePageMethod,
            Method selectionHolderMethod,
            Method selectionLayersMethod,
            Method dataModelSourceMethod,
            Method modelAllArtMeshes,
            Method meshTexExt,
            Method texExtModelImageInput,
            Method modelImageInputGuid,
            Method meshParentGuid) {

        static Optional<UiListHost> bind(final Class<?> listClass) {
            try {
                final ClassLoader loader = listClass.getClassLoader();
                if (loader == null || !UI_LIST_CLASS.equals(listClass.getName())) {
                    return Optional.empty();
                }
                final ListBytecodeInfo info = inspectUiListBytecode(listClass);
                if (info == null) return Optional.empty();

                final Field dataModelField = accessibleField(listClass, FIELD_DATA_MODEL);
                final Field comboField = accessibleField(listClass, FIELD_COMBO);
                final Field allImagesField = accessibleField(listClass, FIELD_ALL_IMAGES);
                final Field listWidgetField = accessibleField(listClass, FIELD_LIST_WIDGET);
                final Field filterPartsField = optionalField(listClass, FIELD_FILTER_PARTS);

                final Class<?> switchClass = loader.loadClass(UI_LIST_CLASS + "$e");
                final Field switchField = switchClass.getDeclaredField(FIELD_SWITCH_MAP);
                switchField.setAccessible(true);
                final int[] switchMap = (int[]) switchField.get(null);

                final Class<?> itemClass = loader.loadClass(UI_LIST_CLASS + "$d");
                final Method itemImage = itemClass.getMethod(METHOD_ITEM_IMAGE);
                final Class<?> modelImageClass = itemImage.getReturnType();
                final Method imageGuid = modelImageClass.getMethod(METHOD_MODEL_IMAGE_GUID);

                final Class<?> listModelClass = loader.loadClass(LIST_MODEL_CLASS);
                final Constructor<?> listModelCtor = listModelClass.getConstructor(List.class);

                final Class<?> editLayerClass = loader.loadClass(EDIT_LAYER_CLASS);
                final Method layerModelImage = editLayerClass.getMethod(METHOD_LAYER_MODEL_IMAGE);

                final Class<?> dataModelClass = dataModelField.getType();
                final Method pagesMethod = dataModelClass.getMethod(info.pagesMethodName);
                final Class<?> pageClass = loader.loadClass(info.pageInternalName.replace('/', '.'));
                final Method containerLayerMethod = pageClass.getMethod(info.containerLayerMethodName);
                final Method childrenMethod =
                        containerLayerMethod.getReturnType().getMethod(METHOD_CHILDREN);
                final Class<?> refClass = loader.loadClass(LAYER_REF_CLASS);
                final Method refLayerMethod = refClass.getMethod(METHOD_REF_LAYER);

                final Method activePageMethod = dataModelClass.getMethod(info.activePageMethodName);
                final Method selectionHolderMethod = pageClass.getMethod(info.selectionHolderMethodName);
                final Method selectionLayersMethod =
                        selectionHolderMethod.getReturnType().getMethod(METHOD_SELECTED_LAYERS);

                Method dataModelSource = null;
                Method allArtMeshes = null;
                Method meshTexExt = null;
                Method texExtInput = null;
                Method inputGuid = null;
                Method meshParentGuid = null;
                if (filterPartsField != null && info.modelSourceMethodName != null) {
                    dataModelSource = dataModelClass.getMethod(info.modelSourceMethodName);
                    final Class<?> srcClass = dataModelSource.getReturnType();
                    allArtMeshes = srcClass.getMethod(METHOD_ALL_ART_MESHES);
                    final Class<?> artMeshClass =
                            loader.loadClass("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource");
                    meshTexExt = artMeshClass.getMethod(METHOD_TEX_INPUT_EXT);
                    final Class<?> extClass = meshTexExt.getReturnType();
                    texExtInput = extClass.getMethod(METHOD_MODEL_IMAGE_INPUT);
                    final Class<?> inputClass = texExtInput.getReturnType();
                    inputGuid = inputClass.getMethod(METHOD_ENTRY_GUID);
                    meshParentGuid = artMeshClass.getMethod(METHOD_PARENT_GUID);
                }

                return Optional.of(new UiListHost(
                        dataModelField,
                        comboField,
                        allImagesField,
                        listWidgetField,
                        filterPartsField,
                        switchMap,
                        comboField.getType().getMethod(METHOD_GET_SELECTED_ITEM),
                        listWidgetField.getType().getMethod(METHOD_SET_MODEL, listModelClass),
                        listWidgetField.getType().getMethod(METHOD_REPAINT),
                        listModelCtor,
                        itemImage,
                        imageGuid,
                        listClass.getMethod(METHOD_IS_VISIBLE, itemClass),
                        editLayerClass,
                        layerModelImage,
                        pagesMethod,
                        containerLayerMethod,
                        childrenMethod,
                        refLayerMethod,
                        activePageMethod,
                        selectionHolderMethod,
                        selectionLayersMethod,
                        dataModelSource,
                        allArtMeshes,
                        meshTexExt,
                        texExtInput,
                        inputGuid,
                        meshParentGuid));
            } catch (ReflectiveOperationException | IOException | LinkageError | SecurityException failure) {
                return Optional.empty();
            }
        }

        boolean updateList(final Object listWidget) throws ReflectiveOperationException {
            final Object selectedItem = getSelectedItem.invoke(comboSelectorField.get(listWidget));
            final int mode = selectedItem == null ? -1 : switchMap[((Enum<?>) selectedItem).ordinal()];
            if (mode < CASE_ALL || mode > CASE_USED_SELECTED) {
                return false;
            }

            @SuppressWarnings("unchecked")
            final List<Object> allItems = (List<Object>) allUsingImagesField.get(listWidget);
            final Object dataModel = taeDataModelField.get(listWidget);

            final List<Object> filteredByMode;
            if (mode == CASE_ALL) {
                filteredByMode = allItems;
            } else {
                final Set<Object> usedImages = collectUsedModelImages(dataModel);
                final Set<Object> selectedGuids = mode == CASE_USED_SELECTED ? collectSelectedGuids(dataModel) : null;
                filteredByMode = new ArrayList<>(allItems.size());
                for (int i = 0; i < allItems.size(); i++) {
                    final Object item = allItems.get(i);
                    final Object img = itemModelImage.invoke(item);
                    final boolean used = usedImages.contains(img);
                    final boolean match =
                            switch (mode) {
                                case CASE_USED -> used;
                                case CASE_UNUSED -> !used;
                                case CASE_UNUSED_VISIBLE -> !used && (boolean) isVisibleMethod.invoke(listWidget, item);
                                case CASE_USED_SELECTED -> used && selectedGuids.contains(modelImageGuid.invoke(img));
                                default -> false;
                            };
                    if (match) {
                        filteredByMode.add(item);
                    }
                }
            }

            final List<Object> finalItems;
            if (filterByPartsField == null) {
                finalItems = filteredByMode;
            } else {
                final List<?> filterParts = (List<?>) filterByPartsField.get(listWidget);
                final Set<?> filterSet = filterParts == null ? null : new HashSet<>(filterParts);
                final Set<Object> matchedImageGuids = collectMatchingPartImageGuids(dataModel, filterSet);
                finalItems = new ArrayList<>(filteredByMode.size());
                for (int i = 0; i < filteredByMode.size(); i++) {
                    final Object item = filteredByMode.get(i);
                    final Object guid = modelImageGuid.invoke(itemModelImage.invoke(item));
                    if (matchedImageGuids.contains(guid)) {
                        finalItems.add(item);
                    }
                }
            }

            final Object listModel = listModelCtor.newInstance(finalItems);
            final Object widget = listModelImageField.get(listWidget);
            setModel.invoke(widget, listModel);
            repaint.invoke(widget);
            return true;
        }

        private Set<Object> collectUsedModelImages(final Object dataModel) throws ReflectiveOperationException {
            final Set<Object> used = new HashSet<>();
            final List<?> pages = (List<?>) pagesMethod.invoke(dataModel);
            for (int i = 0; i < pages.size(); i++) {
                final Object container = containerLayerMethod.invoke(pages.get(i));
                final Object[] refs = (Object[]) containerChildren.invoke(container);
                if (refs == null) continue;
                for (final Object ref : refs) {
                    final Object layer = layerRefGetLayer.invoke(ref);
                    if (editLayerClass.isInstance(layer)) {
                        used.add(layerModelImage.invoke(layer));
                    }
                }
            }
            return used;
        }

        private Set<Object> collectSelectedGuids(final Object dataModel) throws ReflectiveOperationException {
            final Set<Object> selected = new HashSet<>();
            final Object page = activePageMethod.invoke(dataModel);
            final Object holder = selectionHolderMethod.invoke(page);
            final Object[] layers = (Object[]) selectionLayersMethod.invoke(holder);
            if (layers == null) return selected;
            for (final Object layer : layers) {
                if (editLayerClass.isInstance(layer)) {
                    final Object modelImage = layerModelImage.invoke(layer);
                    if (modelImage != null) {
                        selected.add(modelImageGuid.invoke(modelImage));
                    }
                }
            }
            return selected;
        }

        private Set<Object> collectMatchingPartImageGuids(final Object dataModel, final Set<?> filterSet)
                throws ReflectiveOperationException {
            final Set<Object> matched = new HashSet<>();
            final Object modelSource = dataModelSourceMethod.invoke(dataModel);
            final List<?> allMeshes = (List<?>) modelAllArtMeshes.invoke(modelSource);
            for (int i = 0; i < allMeshes.size(); i++) {
                final Object artMesh = allMeshes.get(i);
                final Object ext = meshTexExt.invoke(artMesh);
                if (ext == null) continue;
                final Object input = texExtModelImageInput.invoke(ext);
                if (input == null) continue;
                final Object imageGuid = modelImageInputGuid.invoke(input);
                if (imageGuid == null) continue;
                if (filterSet == null || filterSet.contains(meshParentGuid.invoke(artMesh))) {
                    matched.add(imageGuid);
                }
            }
            return matched;
        }

        private static Field accessibleField(final Class<?> owner, final String name) throws NoSuchFieldException {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static Field optionalField(final Class<?> owner, final String name) {
            try {
                return accessibleField(owner, name);
            } catch (NoSuchFieldException absent) {
                return null;
            }
        }
    }

    private static final class ListBytecodeInfo {
        String helperInternalName;
        String helperUsedMethod;
        String pagesMethodName;
        String pageInternalName;
        String containerLayerMethodName;
        String activePageMethodName;
        String selectionHolderMethodName;
        String modelSourceMethodName;
    }

    private static ListBytecodeInfo inspectUiListBytecode(final Class<?> listClass) throws IOException {
        final ClassLoader loader = listClass.getClassLoader();
        final byte[] listBytes = readResource(loader, UI_LIST_CLASS.replace('.', '/') + CLASS_SUFFIX);
        if (listBytes == null) return null;
        final ListBytecodeInfo info = new ListBytecodeInfo();

        new ClassReader(listBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (METHOD_IS_USED.equals(name)) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String m,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && d.startsWith("(L" + DATA_MODEL_INTERNAL)) {
                                                info.helperInternalName = owner;
                                                info.helperUsedMethod = m;
                                            }
                                        }
                                    };
                                }
                                if (METHOD_IS_SELECTED.equals(name)) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String m,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && DATA_MODEL_INTERNAL.equals(owner)
                                                    && d.startsWith("()L")) {
                                                info.activePageMethodName = m;
                                            } else if (op == Opcodes.INVOKEVIRTUAL
                                                    && info.pageInternalName == null
                                                    && d.equals("()L" + LAYER_SEL_HOLDER_INTERNAL + ";")) {
                                                info.pageInternalName = owner;
                                                info.selectionHolderMethodName = m;
                                            }
                                        }
                                    };
                                }
                                if (METHOD_UPDATE_LIST.equals(name)) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String m,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && DATA_MODEL_INTERNAL.equals(owner)
                                                    && d.equals("()L" + MODEL_SOURCE_CLASS.replace('.', '/') + ";")) {
                                                info.modelSourceMethodName = m;
                                            }
                                        }
                                    };
                                }
                                return null;
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (info.helperInternalName == null
                || info.activePageMethodName == null
                || info.selectionHolderMethodName == null) {
            return null;
        }
        final byte[] helperBytes = readResource(loader, info.helperInternalName + CLASS_SUFFIX);
        if (helperBytes == null) return null;
        new ClassReader(helperBytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String sig,
                                    final String[] ex) {
                                if (!info.helperUsedMethod.equals(name)
                                        || !desc.startsWith("(L" + DATA_MODEL_INTERNAL)) {
                                    return null;
                                }
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            final int op,
                                            final String owner,
                                            final String m,
                                            final String d,
                                            final boolean itf) {
                                        if (op == Opcodes.INVOKEVIRTUAL
                                                && DATA_MODEL_INTERNAL.equals(owner)
                                                && "()Ljava/util/ArrayList;".equals(d)) {
                                            info.pagesMethodName = m;
                                        } else if (op == Opcodes.INVOKEVIRTUAL
                                                && info.pageInternalName.equals(owner)
                                                && ("()L" + CONTAINER_LAYER_INTERNAL + ";").equals(d)) {
                                            info.containerLayerMethodName = m;
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (info.pagesMethodName == null || info.containerLayerMethodName == null) {
            return null;
        }
        return info;
    }

    private static byte[] readResource(final ClassLoader loader, final String path) throws IOException {
        try (InputStream stream = loader.getResourceAsStream(path)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }
}
