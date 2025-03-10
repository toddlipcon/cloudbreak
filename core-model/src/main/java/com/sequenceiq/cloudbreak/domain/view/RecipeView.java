package com.sequenceiq.cloudbreak.domain.view;

import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;

import org.hibernate.annotations.Where;

import com.sequenceiq.cloudbreak.api.endpoint.v4.recipes.requests.RecipeV4Type;

@Entity
@Where(clause = "archived = false")
@Table(name = "Recipe")
public class RecipeView extends CompactView {
    @Enumerated(EnumType.STRING)
    private RecipeV4Type recipeType;

    private String resourceCrn;

    private boolean archived;

    public RecipeV4Type getRecipeType() {
        return recipeType;
    }

    public void setRecipeType(RecipeV4Type recipeType) {
        this.recipeType = recipeType;
    }

    public String getResourceCrn() {
        return resourceCrn;
    }

    public void setResourceCrn(String resourceCrn) {
        this.resourceCrn = resourceCrn;
    }
}
